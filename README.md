# A2A Framework

> 基于 gRPC 的多语言 Agent-to-Agent 通讯框架，支持 Java、Node.js、Python、Go。

[![Java](https://img.shields.io/badge/Java-21+-orange.svg)](https://openjdk.org/)
[![Node.js](https://img.shields.io/badge/Node.js-18+-green.svg)](https://nodejs.org/)
[![Python](https://img.shields.io/badge/Python-3.9+-blue.svg)](https://python.org/)
[![Go](https://img.shields.io/badge/Go-1.21+-cyan.svg)](https://golang.org/)
[![gRPC](https://img.shields.io/badge/gRPC-latest-blue.svg)](https://grpc.io/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![GitHub](https://img.shields.io/badge/GitHub-dongchenglin0%2Fa2a--framework-black.svg)](https://github.com/dongchenglin0/a2a-framework)

---

## 简介

**A2A Framework** 是一个轻量级、生产就绪的 **Agent-to-Agent（A2A）通讯框架**，让不同语言、不同进程中的 AI Agent 能够通过统一的 gRPC 协议互相发现、通讯和协作。

### 核心特性

| 特性 | 说明 |
|------|------|
| 🔍 **服务发现** | Agent 自动注册到 Registry，支持按类型/能力发现 |
| 📨 **多种通讯模式** | 同步请求/响应、单向发送、发布/订阅、任务委托、双向流式 |
| 🔐 **JWT 认证** | 内置 Token 鉴权 + 黑名单 + 多租户支持 |
| 🌐 **四语言 SDK** | Java / Node.js / Python / Go，无缝互通 |
| 🗄️ **注册中心抽象** | 支持内存（开发）/ Redis / Nacos / ETCD（生产） |
| 📊 **Prometheus 监控** | 内置 11 个 Counter + 4 个 Gauge + 3 个 Timer 指标 |
| 🔄 **熔断降级** | 基于 Resilience4j，每个 Agent 独立熔断器 |
| 💾 **消息持久化** | 基于 Redis Stream，支持断点续传 |
| 🤝 **版本协商** | 自动协商协议版本，向后兼容 |
| 🐳 **容器化** | 提供 Dockerfile + Docker Compose 一键启动 |

---

## 架构图

```
┌──────────────────────────────────────────────────────────────────┐
│                        A2A Framework v1.1                        │
│                                                                  │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────┐    │
│  │  Java    │  │ Node.js  │  │  Python  │  │     Go       │    │
│  │  Agent   │  │  Agent   │  │  Agent   │  │   Agent      │    │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └──────┬───────┘    │
│       │              │              │               │            │
│  ┌────▼──────────────▼──────────────▼───────────────▼──────┐    │
│  │          a2a-core / node-sdk / python-sdk / go-sdk       │    │
│  │              ResilientA2AClient (熔断/重试)               │    │
│  └────────────────────────┬─────────────────────────────────┘    │
│                           │ gRPC (TLS/mTLS)                      │
│  ┌────────────────────────▼─────────────────────────────────┐    │
│  │              Registry Service (:9090)                    │    │
│  │   注册 · 发现 · 路由 · 心跳 · 认证 · 版本协商             │    │
│  │   MetricsGrpcInterceptor + AuthGrpcInterceptor           │    │
│  └──────────┬──────────────────────────┬────────────────────┘    │
│             │                          │                         │
│    ┌────────▼────────┐      ┌──────────▼──────────┐             │
│    │  Memory / Redis │      │  Prometheus Metrics  │             │
│    │  Nacos / ETCD   │      │  (:8080/actuator)    │             │
│    └─────────────────┘      └─────────────────────┘             │
└──────────────────────────────────────────────────────────────────┘
```

---

## 模块说明

| 模块 | 说明 |
|------|------|
| `a2a-proto` | Protobuf 协议定义（4 个 gRPC Service + VersionService） |
| `a2a-registry` | Agent 注册中心（Spring Boot 3.2 + gRPC Server） |
| `a2a-core` | Java Agent SDK（含熔断/重试/PubSub/TaskWatcher） |
| `a2a-node-sdk` | Node.js / TypeScript Agent SDK |
| `a2a-python-sdk` | Python asyncio Agent SDK |
| `a2a-go-sdk` | Go Agent SDK |
| `a2a-demo` | Java 示例代码（AgentADemo、AgentBDemo） |

---

## 快速开始

### 前置要求

- Java 21+
- Docker & Docker Compose（推荐）
- Node.js 18+（如需 Node.js SDK）
- Python 3.9+（如需 Python SDK）
- Go 1.21+（如需 Go SDK）

### 1. 一键启动（Docker Compose）

```bash
git clone https://github.com/dongchenglin0/a2a-framework.git
cd a2a-framework

# 启动 Redis + Registry
docker-compose up -d

# 查看日志
docker-compose logs -f a2a-registry
```

Registry 启动后：
- **gRPC 端口**：`localhost:9090`
- **HTTP 管理端口**：`http://localhost:8080`
- **Prometheus 指标**：`http://localhost:8080/actuator/prometheus`

### 2. 本地构建启动

```bash
# 内存模式（无需 Redis，适合开发调试）
./gradlew :a2a-registry:bootRun

# 或构建 jar 后运行
./gradlew :a2a-registry:bootJar
java -jar a2a-registry/build/libs/a2a-registry-*.jar
```

### 3. Docker 单独构建

```bash
docker build -t a2a-registry:latest .
docker run -p 9090:9090 -p 8080:8080 \
  -e A2A_STORE_MODE=memory \
  a2a-registry:latest
```

---

## SDK 使用示例

### Java SDK

```java
// 构建配置
A2AConfig config = A2AConfig.builder()
    .registryHost("localhost").registryPort(9090)
    .agentId("agent-a").agentName("数据处理Agent")
    .agentType("data-processor").agentHost("localhost").agentPort(8001)
    .capabilities(List.of("sum", "sort"))
    .jwtToken(System.getenv("A2A_JWT_TOKEN"))
    .autoRegister(true)
    .build();

A2AAgent agent = A2AAgent.create(config);

// 注册请求处理器
agent.onMessage(new MessageHandler() {
    @Override
    public Map<String, Object> handleRequest(String fromAgentId,
            String messageId, Map<String, Object> payload) {
        List<Number> numbers = (List<Number>) payload.get("numbers");
        double sum = numbers.stream().mapToDouble(Number::doubleValue).sum();
        return Map.of("sum", sum, "count", numbers.size());
    }
    @Override
    public void handleMessage(String fromAgentId, String topic,
            Map<String, Object> payload) { /* 单向消息 */ }
});

agent.start();

// 向其他 Agent 发送请求
Message response = agent.sendRequest("agent-b", Map.of("numbers", List.of(1,2,3)));
```

### Node.js SDK

```typescript
import { A2AAgent } from '@fox/a2a-node-sdk';

const agent = A2AAgent.create({
  registryHost: 'localhost', registryPort: 9090,
  agentId: 'node-agent', agentType: 'node-worker',
  agentHost: 'localhost', agentPort: 8003,
  jwtToken: process.env.A2A_JWT_TOKEN,
});

agent.onRequest(async (fromAgentId, messageId, payload) => {
  return { result: 'processed', data: payload };
});

await agent.start();
```

### Python SDK

```python
import asyncio
from a2a_sdk import A2AAgent, A2AConfig

async def main():
    config = A2AConfig(
        registry_host="localhost", registry_port=9090,
        agent_id="python-agent", agent_type="python-worker",
        agent_host="localhost", agent_port=8004,
        jwt_token=os.environ.get("A2A_JWT_TOKEN"),
    )
    agent = A2AAgent(config)

    @agent.on_request
    async def handle(from_agent_id, message_id, payload):
        return {"result": "ok", "echo": payload}

    await agent.start()

asyncio.run(main())
```

### Go SDK

```go
import "github.com/dongchenglin0/a2a-framework/a2a-go-sdk/a2a"

agent := a2a.NewAgent(a2a.Config{
    RegistryHost: "localhost", RegistryPort: 9090,
    AgentID: "go-agent", AgentType: "go-worker",
    AgentHost: "localhost", AgentPort: 8005,
    JWTToken: os.Getenv("A2A_JWT_TOKEN"),
})

agent.OnRequest(func(fromAgentID, messageID string, payload map[string]interface{}) (map[string]interface{}, error) {
    return map[string]interface{}{"result": "ok"}, nil
})

agent.Start()
```

---

## 通讯模式

| 模式 | Java | 说明 |
|------|------|------|
| 同步请求/响应 | `agent.sendRequest(targetId, payload)` | 发送请求并等待响应 |
| 单向发送 | `agent.send(targetId, topic, payload)` | Fire-and-forget |
| 发布/订阅 | `agent.publish(topic, payload)` / `agent.subscribe(topic)` | 多播事件 |
| 任务委托 | `agent.delegateTask(targetId, taskType, input)` | 异步任务，可轮询进度 |
| 双向流式 | `agent.stream(targetId, handler)` | 大数据流式传输 |

---

## 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `A2A_STORE_MODE` | 存储模式：`memory` / `redis` / `nacos` / `etcd` | `memory` |
| `A2A_JWT_SECRET` | JWT 签名密钥（生产必须修改，≥32 字符） | — |
| `REDIS_HOST` | Redis 地址 | `localhost` |
| `REDIS_PORT` | Redis 端口 | `6379` |
| `A2A_JWT_TOKEN` | Agent 认证 Token | — |
| `GRPC_PORT` | gRPC 监听端口 | `9090` |
| `SERVER_PORT` | HTTP 管理端口 | `8080` |

---

## 构建命令

```bash
# 构建所有模块
./gradlew build -x test

# 仅构建 Registry jar
./gradlew :a2a-registry:bootJar

# 生成 Proto 代码
./gradlew :a2a-proto:generateProto

# 清理
./gradlew clean
```

---

## 版本历史

### v1.1.0（当前版本）
- ✅ 注册中心抽象：`RegistryProvider` 接口，支持 Memory / Redis / Nacos / ETCD
- ✅ Prometheus 监控：`A2AMetrics` + `MetricsGrpcInterceptor`
- ✅ 安全增强：JWT 黑名单 / 多租户 / Token 刷新 / TLS/mTLS
- ✅ 熔断降级：`ResilientA2AClient`（Resilience4j）
- ✅ 消息持久化：Redis Stream，支持断点续传
- ✅ 版本协商：`VersionGrpcService` + `VersionChecker`
- ✅ 修复所有 Java 编译错误，Docker 构建支持

### v1.0.0
- ✅ 四语言 SDK（Java / Node.js / Python / Go）
- ✅ 5 种通讯模式（同步/单向/PubSub/任务/流式）
- ✅ JWT 认证 + 内存/Redis 双存储

---

## 项目结构

```
a2a-framework/
├── a2a-proto/                    # Protobuf 协议定义
│   └── src/main/proto/a2a.proto  # 核心协议（4 Service + VersionService）
├── a2a-registry/                 # 注册中心（Spring Boot 3.2 + gRPC）
│   └── src/main/java/com/fox/a2a/registry/
│       ├── grpc/                 # gRPC 服务实现
│       ├── provider/             # RegistryProvider 抽象 + 多种实现
│       ├── metrics/              # Prometheus 指标
│       ├── security/             # JWT + TLS
│       └── persistence/          # Redis Stream 消息持久化
├── a2a-core/                     # Java Agent SDK
│   └── src/main/java/com/fox/a2a/core/
│       ├── A2AAgent.java         # Agent 主类
│       ├── A2AClient.java        # gRPC 客户端
│       ├── resilience/           # 熔断降级
│       ├── pubsub/               # PubSub 订阅
│       └── task/                 # 任务监听
├── a2a-node-sdk/                 # Node.js SDK（TypeScript）
├── a2a-python-sdk/               # Python SDK（asyncio）
├── a2a-go-sdk/                   # Go SDK
├── a2a-demo/                     # Java 示例
├── Dockerfile                    # 多阶段构建
├── docker-compose.yml            # 一键启动
└── README.md
```

---

## 常见问题

**Q: Docker build 失败，提示 gradle 命令找不到？**

项目使用 Gradle Wrapper，请确保使用 `./gradlew` 而非 `gradle`。Dockerfile 已修复此问题。

**Q: Agent 无法连接 Registry？**

1. 确认 Registry 已启动（`docker-compose ps`）
2. 检查 `registryHost`/`registryPort` 配置
3. 确认 `A2A_JWT_TOKEN` 已设置
4. 检查防火墙是否放行 9090 端口

**Q: 如何生产部署？**

1. 修改 `A2A_JWT_SECRET` 为强随机密钥（≥32 字符）
2. 设置 `A2A_STORE_MODE=redis` 并配置 Redis
3. 建议在 Registry 前加 TLS 终止（Nginx/Envoy）
4. 多实例部署时共享同一 Redis 实现高可用

---

## 关于作者

本项目由 **Fox** 开发维护。

### 📱 公众号：Fox爱分享

> 分享 AI、编程、架构设计等技术干货，持续更新实战项目经验。

如果这个项目对你有帮助，欢迎：
- ⭐ **Star** 本仓库
- 🔔 关注公众号 **Fox爱分享** 获取更多技术内容
- 🐛 提交 Issue 反馈问题
- 🤝 提交 PR 参与贡献

---

## 许可证

[MIT](LICENSE) © 2024-2026 Fox

> 开源不易，如果本项目帮助到你，请点个 ⭐ Star 支持一下！
