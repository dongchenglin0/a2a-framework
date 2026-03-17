# Changelog

## [1.1.0] - 2026-03-17

### 新增

- **注册中心抽象层**：新增 `RegistryProvider` 顶层接口，支持 SPI 插件化扩展
  - `MemoryRegistryProvider`：内存实现（默认）
  - `RedisRegistryProvider`：Redis 分布式实现
  - `NacosRegistryProvider`：Nacos 3.x 骨架（v1.2 完整实现）
  - `EtcdRegistryProvider`：ETCD 骨架（v1.2 完整实现）
  - `RegistryProviderFactory`：工厂类，统一管理所有提供者

- **Prometheus 监控**：
  - `A2AMetrics`：核心指标（注册/注销/消息/心跳/任务/认证）
  - `MetricsGrpcInterceptor`：gRPC 全局拦截器，自动记录调用指标
  - `AgentMetricsCollector`：定时收集 Agent 状态指标
  - 暴露 `/actuator/prometheus` 端点

- **安全增强**：
  - JWT Token 黑名单（支持 Redis 存储）
  - JWT 密钥轮换支持
  - 多租户 Token（tenantId claim）
  - Token 刷新机制
  - gRPC TLS/mTLS 支持（可选）
  - `AuthGrpcInterceptor`：gRPC 全局认证拦截器

- **熔断降级**（`a2a-core` 模块）：
  - `A2ACircuitBreakerConfig`：基于 Resilience4j 2.2.0 的熔断器配置
    - 每个目标 Agent 独立熔断器实例，互不影响
    - 滑动窗口大小 10，失败率阈值 50%，熔断等待 10s
    - 半开状态允许 3 次探测调用，支持自动从 OPEN 转 HALF_OPEN
  - `ResilientA2AClient`：带熔断/重试的客户端包装类
    - 继承 `A2AClient`，透明增强 `sendRequest` / `delegateTask`
    - 熔断器打开时立即执行降级，不等待超时
    - 支持注入自定义 `FallbackHandler`
  - `FallbackHandler`：自定义降级处理器接口
    - `handleRequestFallback`：请求降级（返回缓存/默认值）
    - `handleTaskFallback`：任务委托降级（默认抛出异常，可覆盖）

- **消息持久化**（`a2a-registry` 模块）：
  - `MessagePersistenceService`：基于 Redis Stream 的 PubSub 消息持久化
    - 仅在 `a2a.persistence.enabled=true` 时启用（`@ConditionalOnProperty`）
    - 支持消息重放：Agent 重新上线后可从指定 offset 消费历史消息
    - 自动修剪 Stream 长度（MAXLEN），防止 Redis 内存无限增长
    - 支持按 topic 白名单过滤，只持久化指定 topic
  - `PersistenceProperties`：持久化配置属性（`a2a.persistence.*`）
    - `enabled`：是否启用（默认 false）
    - `max-messages-per-topic`：每个 topic 最大消息数（默认 10000）
    - `retention-days`：消息保留天数（默认 7 天）
    - `persist-topics`：需要持久化的 topic 白名单（空=全部）

- **协议版本协商**：
  - `VersionService` gRPC 服务（`a2a.proto`）：
    - `GetVersion`：获取服务端框架版本、协议版本和能力集合
    - `NegotiateVersion`：协商协议版本，返回双方兼容的最高版本
  - `VersionGrpcService`：服务端实现（`a2a-registry` 模块）
    - 自动检测客户端版本，对 v1.0.x 返回废弃提示
    - 无交集时返回 `compatible=false`
  - `VersionChecker`：客户端版本检查工具（`a2a-core` 模块）
    - Agent 启动时调用，不兼容时抛出 `IllegalStateException`
    - 旧版 Registry 不支持版本协商时自动忽略（向后兼容）
    - 版本废弃提示通过 WARN 日志输出

### 变更

- `RegistryGrpcService`：依赖从 `AgentStore` 升级为 `RegistryProvider`
- `HeartbeatScheduler`：依赖从 `AgentStore` 升级为 `RegistryProvider`
- `application.yml`：新增 `a2a.registry.provider` 配置项（兼容旧 `store-mode`）
- `a2a-core/build.gradle`：新增 Resilience4j 依赖（circuitbreaker / retry / decorators 2.2.0）
- `a2a-proto/a2a.proto`：新增 `VersionService`、`GetVersionRequest/Response`、`NegotiateVersionRequest/Response` 消息定义

### 修复

- 无

---

## [1.0.0] - 2026-03-16

### 新增

- 初始版本发布
- 支持 Java、Node.js、Python、Go 四种语言 SDK
- 基于 gRPC 的 Agent 注册/发现/通讯
- 支持内存/Redis 双存储模式
- JWT 认证
- PubSub 发布/订阅
- 任务委托与监控
- 流式传输
