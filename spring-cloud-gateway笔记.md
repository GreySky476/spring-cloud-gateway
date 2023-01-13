## 工作机制
- GateWay 接收客户请求
- 客户端请求与路由信息进行匹配，匹配成功的才能够被发往相应的下游服务
- 请求经过 Filter 过滤器链，执行 pre 处理逻辑
- 请求被转发至下游服务并返回响应
- 响应经过 Filter 过滤器链，执行 POST 处理逻辑
- 向客户端响应应答
## 源码基本组件
```text
Route：路由信息
Predicate：谓词
Filter：过滤器

外部配置
RouteDefinition：定制化的 Route
PredicateDefinition：定制化的 predicate
FilterDefinition：定制化的 Filter

RoutePredicateFactor：生产 Predicate
GatewayFilterFactory：生产 GatewayFilter

GatewayFilterFactory 和 RoutePredicateFactor 都实现了 Configurable 接口
- RouteDefinition 用于定义 Route，PredicateDefinition 用于定义 Predicate，FilterDefinition 用于定义 Filter
- 这里涉及到了一个问题，Predicate 属于条件匹配，不管在编码还是配置文件中，都是类似 key-value 的条件对应
- 查看 RoutePredicateFactor 的方法和实现类之一的 AfterRoutePredicateFactory 可知 PredicateDefinition 被转化成了 Config，然后由 apply() 方法进行匹配
以上可得问题，转化 PredicateDefinition 为 Config 的类是哪个？
- RouteLocator 组件，该方法用来获取 Route 信息，外部化配置定义 Route 使用的是 RouteDefinition 组件。同样有配套的 RouteDefinitionLocator 组件
- RouteDefinitionLocator 组件很简单，用来获取 RouteDefinition 组件，由这些可知有个实现类将 RouteDefinition 转化成 Route
- RouteDefinitionRouteLocator 组件，用于将 RouteDefinition 转化成 Route
-- 核心方法：getRoutes()，这个方法将 RouteDefinition 转化成 Route
-- 问题：RouteDefinition 从哪来？
--- 查看 RouteDefinitionRouteLocator 构造方法，发现有个 RouteDefinitionLocator，调用该类的 getRouteDefinitions() 获取 RouteDefinition
--- 问题：RouteDefinitionLocator 中的 RouteDefinition 从哪来？
---- 查看核心类 GatewayAutoConfiguration，有 routeDefinitionLocator() 和 propertiesRouteDefinitionLocator() 方法
---- propertiesRouteDefinitionLocator() 创建 PropertiesRouteDefinitionLocator，参数为 GatewayProperties，暴露出方法 getRouteDefinitions()，返回 Flux<RouteDefinition>
----- GatewayProperties：表示以 spring.cloud.gateway 前缀的 properties 会绑定 GatewayProperties
---- routeDefinitionLocator() 创建 CompositeRouteDefinitionLocator，参数需要接收 Flux<RouteDefinition>，暴露方法 getRouteDefinitions()，返回 Flux<RouteDefinition>
----- 方便用户自定义 RouteDefinition
```
## GatewayAutoConfiguration 自动装配类整理的类图
[img/8c891d90b4428241cb1350121db1d925.png]
