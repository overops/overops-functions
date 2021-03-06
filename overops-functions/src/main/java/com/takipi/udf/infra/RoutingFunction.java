package com.takipi.udf.infra;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.takipi.api.client.ApiClient;
import com.takipi.api.client.request.event.EventsRequest;
import com.takipi.api.client.request.label.BatchModifyLabelsRequest;
import com.takipi.api.client.result.EmptyResult;
import com.takipi.api.client.result.event.EventResult;
import com.takipi.api.client.result.event.EventsResult;
import com.takipi.api.client.util.category.CategoryUtil;
import com.takipi.api.client.util.infra.Categories;
import com.takipi.api.client.util.infra.Categories.Category;
import com.takipi.api.client.util.infra.Categories.CategoryType;
import com.takipi.api.client.util.infra.InfraUtil;
import com.takipi.api.client.util.settings.ServiceSettingsData;
import com.takipi.api.client.util.settings.SettingsUtil;
import com.takipi.api.core.url.UrlClient.Response;
import com.takipi.common.util.CollectionUtil;
import com.takipi.common.util.Pair;
import com.takipi.udf.ContextArgs;
import com.takipi.udf.input.Input;

public class RoutingFunction {
	static RoutingInput getRoutingInput(String rawInput) {
		System.out.println("validateInput rawInput:" + rawInput);

		if (Strings.isNullOrEmpty(rawInput)) {
			throw new IllegalArgumentException("Input is empty");
		}

		RoutingInput input;

		try {
			input = RoutingInput.of(rawInput);
		} catch (Exception e) {
			throw new IllegalArgumentException(e.getMessage(), e);
		}

		if (StringUtils.isEmpty(input.category_name)) {
			throw new IllegalArgumentException("'category_name' can't be empty");
		}

		return input;
	}

	static void install(String rawContextArgs, RoutingInput input) {
		ContextArgs args = (new Gson()).fromJson(rawContextArgs, ContextArgs.class);

		System.out.println("install context: " + rawContextArgs);

		if (!args.validate()) {
			throw new IllegalArgumentException("Bad context args - " + rawContextArgs);
		}

		if (!args.viewValidate()) {
			return;
		}

		ApiClient apiClient = args.apiClient();

		DateTime to = DateTime.now();
		DateTime from = to.minusDays(30);

		DateTimeFormatter fmt = ISODateTimeFormat.dateTime().withZoneUTC();

		boolean includeStacktrace = (input.routing_type == CategoryType.app);

		EventsRequest eventsRequest = EventsRequest.newBuilder().setServiceId(args.serviceId).setViewId(args.viewId)
				.setIncludeStacktrace(includeStacktrace).setFrom(from.toString(fmt)).setTo(to.toString(fmt)).build();

		Response<EventsResult> eventsResponse = apiClient.get(eventsRequest);

		if (eventsResponse.isBadResponse()) {
			throw new IllegalStateException("Failed getting view events.");
		}

		EventsResult eventsResult = eventsResponse.data;

		if (CollectionUtil.safeIsEmpty(eventsResult.events)) {
			return;
		}

		String categoryId = CategoryUtil.createCategory(input.category_name, args.serviceId, apiClient);
		Map<CategoryType, String> categoryIds = Collections.singletonMap(input.routing_type, categoryId);

		Categories categories = getCategories(apiClient, args.serviceId, input);

		Set<String> createdLabels = Sets.newHashSet();

		boolean hasModifications = false;
		BatchModifyLabelsRequest.Builder builder = BatchModifyLabelsRequest.newBuilder().setServiceId(args.serviceId)
				.setHandleSimilarEvents(input.handleSimilarEvents());

		for (EventResult event : eventsResult.events) {
			Pair<Collection<String>, Collection<String>> eventCategories = InfraUtil.categorizeEvent(event,
					args.serviceId, categoryIds, categories, createdLabels, apiClient, false, false);

			Collection<String> labelsToAdd = eventCategories.getFirst();
			Collection<String> labelsToRemove = eventCategories.getSecond();

			if ((!labelsToAdd.isEmpty()) || (!labelsToRemove.isEmpty())) {
				builder.addLabelModifications(event.id, labelsToAdd, labelsToRemove);
				hasModifications = true;
			}
		}

		if (!hasModifications) {
			return;
		}

		Response<EmptyResult> response = apiClient.post(builder.build());

		if (response.isBadResponse()) {
			throw new IllegalStateException("Failed batch apply of labels.");
		}
	}

	static void execute(String rawContextArgs, RoutingInput input) {
		ContextArgs args = (new Gson()).fromJson(rawContextArgs, ContextArgs.class);

		System.out.println("execute context: " + rawContextArgs);

		if (!args.validate()) {
			throw new IllegalArgumentException("Bad context args - " + rawContextArgs);
		}

		if (!args.eventValidate()) {
			return;
		}

		ApiClient apiClient = args.apiClient();

		String categoryId = CategoryUtil.createCategory(input.category_name, args.serviceId, apiClient);
		Map<CategoryType, String> categoryIds = Collections.singletonMap(input.routing_type, categoryId);

		Categories categories = getCategories(apiClient, args.serviceId, input);

		InfraUtil.categorizeEvent(args.eventId, args.serviceId, categoryIds, categories, Sets.newHashSet(), apiClient,
				true, input.handleSimilarEvents());
	}

	private static Categories getCategories(ApiClient apiClient, String serviceId, RoutingInput input) {
		Collection<Category> categories = Lists.newArrayList();

		if (!CollectionUtil.safeIsEmpty(input.getCategories())) {
			categories.addAll(input.getCategories());
		}

		ServiceSettingsData settings = SettingsUtil.getServiceReliabilitySettings(apiClient, serviceId);

		if ((settings != null) && (!CollectionUtil.safeIsEmpty(settings.tiers))) {
			Categories.fillMissingCategoryNames(settings.tiers);
			categories.addAll(settings.tiers);
		}

		return Categories.expandWithDefaultCategories(categories);
	}

	static class RoutingInput extends Input {
		public List<String> namespaces;
		public String template_view;
		public String category_name;
		public CategoryType routing_type;

		private final List<Category> categories;

		RoutingInput(String raw) {
			super(raw);

			if (this.routing_type == null) {
				routing_type = CategoryType.infra;
			}

			this.categories = processNamespaces();
		}

		public List<Category> getCategories() {
			return categories;
		}

		public boolean handleSimilarEvents() {
			return (routing_type != CategoryType.app);
		}

		private List<Category> processNamespaces() {
			if (CollectionUtil.safeIsEmpty(namespaces)) {
				return Collections.emptyList();
			}

			List<Category> result = Lists.newArrayList();

			for (String namespace : namespaces) {
				int index = namespace.indexOf('=');

				if ((index <= 0) || (index > namespace.length() - 1)) {
					throw new IllegalArgumentException("Invalid namespaces");
				}

				String key = StringUtils.trim(namespace.substring(0, index));
				String value = StringUtils.trim(namespace.substring(index + 1, namespace.length()));

				if ((key.isEmpty()) || (value.isEmpty())) {
					throw new IllegalArgumentException("Invalid namespaces");
				}

				Category category = new Category();
				category.names = Collections.singletonList(key);
				category.labels = Collections.singletonList(value);
				category.type = routing_type;

				result.add(category);
			}

			return result;
		}

		static RoutingInput of(String raw) {
			return new RoutingInput(raw);
		}
	}
}
