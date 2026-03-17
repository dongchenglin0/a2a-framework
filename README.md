# A2A Framework

> 基于 gRPC 的 Agent-to-Agent 通讯框架，支持 Java 和 Node.js。

[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://openjdk.org/)
[![Node.js](https://img.shields.io/badge/Node.js-18+-green.svg)](https://nodejs.org/)
[![gRPC](https://img.shields.io/badge/gRPC-latest-blue.svg)](https://grpc.io/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## 简介

A2A Framework 是一个轻量级的 **Agent-to-Agent（A2A）通讯框架**，让不同语言、不同进程中的 Agent 能够通过统一的 gRPC 协议互相发现、通讯和协作。

**核心特性：**
- 🔍 **服务发现**：Agent 自动注册到 Registry，支持按类型发现
- 📨 **多种通讯模式**：同步请求/响应、单向发送、发布/订阅、任务委托、流式传输
- 🔐 **JWT 认证**：内置 Token 鉴权，保障通讯安全
- 🌐 **跨语言**：Java SDK + Node.js SDK，轻松互通
- 🗄️ **灵活存储**：支持内存模式（开发）和 Redis 模式（生产）

---

## 架构图

```
┌─────────────────────────────────────────────────────┐
│                   A2A Framework                      │
│                                                      │
│  ┌──────────┐    ┌──────────┐    ┌──────────────┐   │
│  │ Java     │    │ Java     │    │  Node.js     │   │
│  │ Agent A  │    │ Agent B  │    │  Agent C     │   │
│  └────┬─────┘    └────┬─────┘    └──────┬───────┘   │
│       │               │                 │            │
│  ┌────▼───────────────▼─────────────────▼──────┐    │
│  │              a2a-core / node-sdk             │    │
│  └────────────────────┬─────────────────────────┘    │
│                       │ gRPC                         │
│  ┌────────────────────▼─────────────────────────┐    │
│  │           Registry Service (:9090)            │    │
│  │     注册 · 发现 · 路由 · 心跳 · 认证           │    │
│  └────────────────────┬─────────────────────────┘    │
│                       │                              │
│              ┌────────▼────────┐                     │
│              │  Redis / Memory │                     │
│              └─────────────────┘                     │
└─────────────────────────────────────────────────────┘
```

---

## 模块说明

| 模块 | 说明 |
|------|------|
| `a2a-proto` | Protobuf 协议定义（`.proto` 文件及生成代码） |
| `a2a-registry` | Agent 注册中心（Spring Boot + gRPC Server） |
| `a2a-core` | Java Agent SDK（核心通讯库） |
| `a2a-node-sdk` | Node.js Agent SDK（TypeScript） |
| `a2a-demo` | Java 示例代码（AgentA、AgentB） |

---

## 快速开始

### 前置要求

- Java 17+
- Node.js 18+（如需使用 Node.js SDK）
- Docker & Docker Compose（推荐，用于启动 Registry）

### 1. 启动 Registry

**方式一：Docker Compose（推荐）**

```bash
# 启动 Redis + Registry
docker-compose up -d

# 查看日志
docker-compose logs -f a2a-registry
```

**方式二：本地启动（需要 Java 17+）**

```bash
# 内存模式（无需 Redis，适合开发调试）
./gradlew :a2a-registry:bootRun
```

Registry 启动后：
- HTTP API：`http://localhost:8080`
- gRPC 端口：`localhost:9090`

### 2. 生成 JWT Token

```bash
# 使用 Registry 提供的 REST 接口生成 Token
curl -X POST http://localhost:8080/api/token \
  -H "Content-Type: application/json" \
  -d '{"agentId":"my-agent","agentType":"worker"}'
```

将返回的 Token 设置为环境变量：

```bash
export A2A_JWT_TOKEN=<your-token>
```

### 3. Java Agent 示例

```java
// 构建配置
A2AConfig config = A2AConfig.builder()
    .registryHost("localhost")
    .registryPort(9090)
    .agentId("my-agent")
    .agentName("我的Agent")
    .agentType("worker")
    .agentHost("localhost")
    .agentPort(8001)
    .jwtToken(System.getenv("A2A_JWT_TOKEN"))
    .autoRegister(true)
    .build();

// 创建并配置 Agent
A2AAgent agent = A2AAgent.create(config);

// 注册请求处理器
agent.onMessage((fromAgentId, messageId, payload) -> {
    // 处理请求，返回响应
    return Map.of("result", "ok");
});

// 启动
agent.start();
```

运行完整示例：

```bash
# 先启动 Agent A（数据处理方）
./gradlew :a2a-demo:run --main-class=com.fox.a2a.demo.AgentADemo

# 再启动 Agent B（协调方，会自动调用 Agent A）
./gradlew :a2a-demo:run --main-class=com.fox.a2a.demo.AgentBDemo
```

### 4. Node.js Agent 示例

```typescript
import { A2AAgent, A2AConfig } from '@fox/a2a-node-sdk';

const agent = A2AAgent.create({
  registryHost: 'localhost',
  registryPort: 9090,
  agentId: 'node-agent',
  agentName: 'Node Agent',
  agentType: 'node-worker',
  agentHost: 'localhost',
  agentPort: 8003,
  jwtToken: process.env.A2A_JWT_TOKEN,
});

// 注册请求处理器
agent.onRequest(async (fromAgentId, messageId, payload) => {
  return { result: 'ok' };
});

await agent.start();
console.log('Node Agent started!');
```

运行 Node.js 示例：

```bash
cd a2a-node-sdk
npm install
npx ts-node examples/node-agent-demo.ts
```

---

## 通讯模式

| 模式 | Java 方法 | Node.js 方法 | 说明 |
|------|-----------|--------------|------|
| 同步请求/响应 | `agent.sendRequest(targetId, payload)` | `agent.sendRequest(targetId, payload)` | 发送请求并等待对方返回结果 |
| 单向发送 | `agent.send(targetId, payload)` | `agent.send(targetId, payload)` | fire-and-forget，不等待响应 |
| 发布/订阅 | `agent.publish(topic, payload)` / `agent.subscribe(topic, handler)` | 同左 | 多播事件，支持多订阅者 |
| 任务委托 | `agent.delegateTask(targetId, taskType, input)` | `agent.delegateTask(targetId, taskType, input)` | 异步任务，可轮询进度和结果 |
| 流式传输 | `agent.stream(targetId, handler)` | `agent.stream(targetId, handler)` | 双向数据流，适合大数据传输 |

### 示例：同步请求/响应

```java
// 发送方（Agent B）
Map<String, Object> response = agent.sendRequest(
    "agent-a",
    Map.of("numbers", List.of(1, 2, 3, 4, 5))
);
System.out.println("sum=" + response.get("sum")); // sum=15.0

// 接收方（Agent A）
agent.onMessage((fromAgentId, messageId, payload) -> {
    List<Number> numbers = (List<Number>) payload.get("numbers");
    double sum = numbers.stream().mapToDouble(Number::doubleValue).sum();
    return Map.of("sum", sum, "count", numbers.size());
});
```

### 示例：任务委托

```java
// 委托任务
String taskId = agent.delegateTask("agent-a", "sort-task",
    Map.of("data", List.of(5, 3, 1, 4, 2)));

// 轮询任务状态
Thread.sleep(1000);
TaskResult task = agent.getClient().getTaskStatus(taskId);
System.out.println("status=" + task.getStatus()); // COMPLETED
System.out.println("result=" + task.getOutput());  // {sorted=[1,2,3,4,5]}
```

### 示例：发布/订阅

```java
// 订阅方
agent.subscribe("data.events", (topic, publisherAgentId, payload) -> {
    System.out.println("收到事件: " + payload);
});

// 发布方
agent.publish("data.events", Map.of(
    "event", "processing-complete",
    "timestamp", System.currentTimeMillis()
));
```

---

## 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `A2A_STORE_MODE` | 存储模式：`memory`（开发）/ `redis`（生产） | `memory` |
| `A2A_JWT_SECRET` | JWT 签名密钥（生产环境必须修改，至少 32 字符） | — |
| `REDIS_HOST` | Redis 服务地址 | `localhost` |
| `REDIS_PORT` | Redis 服务端口 | `6379` |
| `A2A_JWT_TOKEN` | Agent 连接 Registry 时使用的认证 Token | — |

---

## 构建

```bash
# 构建所有模块
./gradlew build

# 仅构建 Registry
./gradlew :a2a-registry:bootJar

# 跳过测试构建
./gradlew build -x test

# 清理构建产物
./gradlew clean
```

---

## 项目结构

```
a2a-framework/
├── a2a-proto/                          # Protobuf 协议定义
│   ├── src/main/proto/
│   │   └── a2a.proto                   # 核心协议文件
│   └── build.gradle
├── a2a-registry/                       # Agent 注册中心
│   ├── src/main/java/com/fox/a2a/registry/
│   ├── Dockerfile
│   └── build.gradle
├── a2a-core/                           # Java Agent SDK
│   ├── src/main/java/com/fox/a2a/core/
│   │   ├── A2AAgent.java               # Agent 主类
│   │   └── A2AConfig.java              # 配置类
│   └── build.gradle
├── a2a-node-sdk/                       # Node.js Agent SDK
│   ├── src/
│   ├── examples/
│   │   └── node-agent-demo.ts          # Node.js 示例
│   └── package.json
├── a2a-demo/                           # Java 示例代码
│   ├── src/main/java/com/fox/a2a/demo/
│   │   ├── AgentADemo.java             # 数据处理 Agent 示例
│   │   └── AgentBDemo.java             # 协调 Agent 示例
│   └── build.gradle
├── gradle/wrapper/
│   └── gradle-wrapper.properties
├── build.gradle                        # 根构建配置
├── settings.gradle                     # 项目模块配置
├── docker-compose.yml                  # Docker 快速启动
└── README.md
```

---

## 常见问题

**Q: Agent 启动后无法连接 Registry？**

检查以下几点：
1. Registry 是否已启动（`docker-compose ps` 或检查进程）
2. `registryHost` 和 `registryPort` 配置是否正确
3. `A2A_JWT_TOKEN` 环境变量是否已设置
4. 防火墙是否放行了 9090 端口

**Q: 如何在生产环境部署？**

1. 修改 `A2A_JWT_SECRET` 为强随机密钥（至少 32 字符）
2. 将 `A2A_STORE_MODE` 设置为 `redis`，并配置 Redis 连接
3. 建议在 Registry 前加 TLS 终止层（如 Nginx/Envoy）
4. 为 Redis 配置密码和持久化策略

**Q: 如何扩展 Registry 实现高可用？**

Registry 使用 Redis 作为共享存储时，可以水平扩展多个 Registry 实例，通过负载均衡（如 Nginx）分发 gRPC 流量。

---

## 许可证

[MIT](LICENSE) © 2024 Fox A2A Team
