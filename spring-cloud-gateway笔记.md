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
```
