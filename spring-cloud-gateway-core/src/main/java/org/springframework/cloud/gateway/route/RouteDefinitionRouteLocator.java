/*
 * Copyright 2013-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.springframework.cloud.gateway.route;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.event.FilterArgsEvent;
import org.springframework.cloud.gateway.event.PredicateArgsEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.handler.AsyncPredicate;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.support.ConfigurationUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.validation.Validator;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Flux;

/**
 * RouteLocator 主要实现类，用于将 RouteDefinition 转换成 route
 *
 * {@link RouteLocator} that loads routes from a {@link RouteDefinitionLocator}
 * @author Spencer Gibb
 */
public class RouteDefinitionRouteLocator implements RouteLocator, BeanFactoryAware, ApplicationEventPublisherAware {
	protected final Log logger = LogFactory.getLog(getClass());

	private final RouteDefinitionLocator routeDefinitionLocator;
	private final Map<String, RoutePredicateFactory> predicates = new LinkedHashMap<>();
	private final Map<String, GatewayFilterFactory> gatewayFilterFactories = new HashMap<>();
	private final GatewayProperties gatewayProperties;
	private final SpelExpressionParser parser = new SpelExpressionParser();
	private BeanFactory beanFactory;
	private ApplicationEventPublisher publisher;

	public RouteDefinitionRouteLocator(RouteDefinitionLocator routeDefinitionLocator,// RouteDefinitionLocator 对象
									   List<RoutePredicateFactory> predicates,// predicates factories 工厂列表，key 为 name，value 为 factory 的 map
									   List<GatewayFilterFactory> gatewayFilterFactories,// filter factory 工厂列表，同样映射成 map
									   GatewayProperties gatewayProperties// 外部化配置类
	) {

		this.routeDefinitionLocator = routeDefinitionLocator;
		initFactories(predicates);
		gatewayFilterFactories.forEach(factory -> this.gatewayFilterFactories.put(factory.name(), factory));
		this.gatewayProperties = gatewayProperties;
	}

	@Autowired
	private Validator validator;

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

	@Override
	public void setApplicationEventPublisher(ApplicationEventPublisher publisher) {
		this.publisher = publisher;
	}

	private void initFactories(List<RoutePredicateFactory> predicates) {
		predicates.forEach(factory -> {
			String key = factory.name();
			if (this.predicates.containsKey(key)) {
				this.logger.warn("A RoutePredicateFactory named "+ key
						+ " already exists, class: " + this.predicates.get(key)
						+ ". It will be overwritten.");
			}
			this.predicates.put(key, factory);
			if (logger.isInfoEnabled()) {
				logger.info("Loaded RoutePredicateFactory [" + key + "]");
			}
		});
	}

	// 实现 RouteLocator 的 getRoutes() 方法
	@Override
	public Flux<Route> getRoutes() {
		return this.routeDefinitionLocator.getRouteDefinitions()
				// convertToRoute 方法将 RouteDefinition 转换成 Route
				.map(this::convertToRoute)
				//TODO: error handling
				.map(route -> {
					if (logger.isDebugEnabled()) {
						logger.debug("RouteDefinition matched: " + route.getId());
					}
					return route;
				});


		/* TODO: trace logging
			if (logger.isTraceEnabled()) {
				logger.trace("RouteDefinition did not match: " + routeDefinition.getId());
			}*/
	}

	private Route convertToRoute(RouteDefinition routeDefinition) {
		// 将 PredicateDefinition 转换成 AsyncPredicate<ServerWebExchange>
		AsyncPredicate<ServerWebExchange> predicate = combinePredicates(routeDefinition);
		// 将 FilterDefinition 转换成 GatewayFilter
		List<GatewayFilter> gatewayFilters = getFilters(routeDefinition);

		// 合并 AsyncPredicate<ServerWebExchange>、GatewayFilter 生成 Route 对象
		return Route.async(routeDefinition)
				.asyncPredicate(predicate)
				.replaceFilters(gatewayFilters)
				.build();
	}

	@SuppressWarnings("unchecked")
	List<GatewayFilter> loadGatewayFilters(String id, List<FilterDefinition> filterDefinitions) {
		ArrayList<GatewayFilter> ordered = new ArrayList<>(filterDefinitions.size());
		for (int i = 0; i < filterDefinitions.size(); i++) {
			// 遍历获取 FilterDefinition
			FilterDefinition definition = filterDefinitions.get(i);
			// 根据名称获取对应 GatewayFilterFactory
			GatewayFilterFactory factory = this.gatewayFilterFactories.get(definition.getName());
			if (factory == null) {
				throw new IllegalArgumentException("Unable to find GatewayFilterFactory with name " + definition.getName());
			}
			// 获取参数
			Map<String, String> args = definition.getArgs();
			if (logger.isDebugEnabled()) {
				logger.debug("RouteDefinition " + id + " applying filter " + args + " to " + definition.getName());
			}
			// 对 args 进一步转换，key 为 config 类（工厂通过泛型指定）的属性名称
			Map<String, Object> properties = factory.shortcutType().normalize(args, factory, this.parser, this.beanFactory);
			// 创建 Config
			Object configuration = factory.newConfig();
			// config 绑定 properties
			ConfigurationUtils.bind(configuration, properties,
					factory.shortcutFieldPrefix(), definition.getName(), validator);
			// 将 config 作为参数传入，调用 factory 的 applyAsync 方法创建 GatewayFilter 对象
			GatewayFilter gatewayFilter = factory.apply(configuration);
			if (this.publisher != null) {
				this.publisher.publishEvent(new FilterArgsEvent(this, id, properties));
			}
			// 如果实现了 Ordered 接口，则直接添加，否则创建为 OrderedGatewayFilter，序号递增
			if (gatewayFilter instanceof Ordered) {
				ordered.add(gatewayFilter);
			}
			else {
				ordered.add(new OrderedGatewayFilter(gatewayFilter, i + 1));
			}
		}
		// 返回 List<GatewayFilter>
		return ordered;
	}

	private List<GatewayFilter> getFilters(RouteDefinition routeDefinition) {
		List<GatewayFilter> filters = new ArrayList<>();

		// 如果没有自定义 Filters，加载 GatewayProperties 中默认的 DefaultFilters
		//TODO: support option to apply defaults after route specific filters?
		if (!this.gatewayProperties.getDefaultFilters().isEmpty()) {
			filters.addAll(loadGatewayFilters("defaultFilters",
					this.gatewayProperties.getDefaultFilters()));
		}

		if (!routeDefinition.getFilters().isEmpty()) {
			// 将 RouteDefinition 中的 filters 转成为 GatewayFilter
			filters.addAll(loadGatewayFilters(routeDefinition.getId(), routeDefinition.getFilters()));
		}

		// 对 GatewayFilter 排序，具体查看 Ordered 接口
		AnnotationAwareOrderComparator.sort(filters);
		return filters;
	}

	private AsyncPredicate<ServerWebExchange> combinePredicates(RouteDefinition routeDefinition) {
		List<PredicateDefinition> predicates = routeDefinition.getPredicates();
		// 调用 lookup，将列表第一个转换成 AsyncPredicate
		AsyncPredicate<ServerWebExchange> predicate = lookup(routeDefinition, predicates.get(0));

		// 循环调用，将列表中每一个 PredicateDefinition 都转成成 AsyncPredicate
		for (PredicateDefinition andPredicate : predicates.subList(1, predicates.size())) {
			AsyncPredicate<ServerWebExchange> found = lookup(routeDefinition, andPredicate);
			// 应用 and 操作，将所有的 AsyncPredicate 组合成一个 AsyncPredicate 对象
			predicate = predicate.and(found);
		}

		return predicate;
	}

	@SuppressWarnings("unchecked")
	private AsyncPredicate<ServerWebExchange> lookup(RouteDefinition route, PredicateDefinition predicate) {
		// 根据名称获取 RoutePredicateFactory 对象
		RoutePredicateFactory<Object> factory = this.predicates.get(predicate.getName());
		if (factory == null) {
            throw new IllegalArgumentException("Unable to find RoutePredicateFactory with name " + predicate.getName());
		}
		// 获取 PredicateDefinition 中的 Map 类型参数，Key 是固定字符串 _genkey_ + 下标拼接而成
		Map<String, String> args = predicate.getArgs();
		if (logger.isDebugEnabled()) {
			logger.debug("RouteDefinition " + route.getId() + " applying "
					+ args + " to " + predicate.getName());
		}

		// 对 args 进一步转换，key 为 config 类（工厂通过泛型指定）的属性名称
        Map<String, Object> properties = factory.shortcutType().normalize(args, factory, this.parser, this.beanFactory);
		// 调用 factory 的 newConfig() 方法创建一个 config 对象
        Object config = factory.newConfig();
        // 将 properties 绑定到 config 对象中
        ConfigurationUtils.bind(config, properties,
                factory.shortcutFieldPrefix(), predicate.getName(), validator);
        if (this.publisher != null) {
            this.publisher.publishEvent(new PredicateArgsEvent(this, route.getId(), properties));
        }
        // 将 config 作为参数传入，调用 factory 的 applyAsync 方法创建 AsyncPredicate 对象
        return factory.applyAsync(config);
	}
}
