# A2A Go SDK

A2A（Agent-to-Agent）框架的 Go 语言 SDK，提供 Agent 注册、发现、消息通信、任务委托和发布/订阅等核心能力。

## 特性

- 🔌 **Agent 注册与发现** — 自动注册到 Registry，按类型/能力发现其他 Agent
- 📨 **同步请求/响应** — 类 RPC 的同步通信模式
- 📢 **单向消息** — Fire-and-forget 异步消息
- 📡 **发布/订阅** — 基于 topic 的 PubSub，支持断线自动重连
- ⚙️ **任务委托** — 异步任务分发与状态追踪
- 💓 **自动心跳** — 后台 goroutine 定期维持 Registry 连接
- 🔗 **连接池** — 复用到各 Agent 的 gRPC 连接

## 安装

```bash
go get github.com/dongchenglin0/a2a-framework/a2a-go-sdk
```

## 生成 Proto 代码

SDK 依赖由 `a2a.proto` 生成的 Go 代码，需先生成后才能编译：

```bash
# 安装 protoc 插件
go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest

# 生成代码（假设 a2a-proto 与 a2a-go-sdk 同级）
bash generate_proto.sh
```

生成的文件位于 `proto/` 目录下。

## 快速开始

### 创建一个 Agent

```go
package main

import (
    "context"
    "log/slog"
    "os"
    "os/signal"
    "syscall"

    a2a "github.com/dongchenglin0/a2a-framework/a2a-go-sdk"
)

func main() {
    cfg := a2a.DefaultConfig()
    cfg.AgentID   = "my-agent"
    cfg.AgentName = "我的Agent"
    cfg.AgentType = "worker"
    cfg.AgentPort = 8080
    cfg.JWTToken  = os.Getenv("A2A_JWT_TOKEN")

    agent := a2a.NewAgent(cfg)

    // 处理同步请求
    agent.OnRequest(func(fromAgentID, messageID string, payload map[string]any) (map[string]any, error) {
        return map[string]any{"echo": payload}, nil
    })

    // 处理任务
    agent.OnTask(func(taskID, taskType, delegatorID string, input map[string]any) (map[string]any, error) {
        slog.Info("执行任务", "taskID", taskID, "type", taskType)
        return map[string]any{"done": true}, nil
    })

    ctx := context.Background()
    if err := agent.Start(ctx); err != nil {
        slog.Error("启动失败", "error", err)
        os.Exit(1)
    }

    quit := make(chan os.Signal, 1)
    signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
    <-quit
    agent.Close(ctx)
}
```

### 发送请求

```go
// 发现 Agent
agents, err := agent.Discover(ctx, "worker")

// 同步请求
result, err := agent.SendRequest(ctx, "target-agent-id", map[string]any{
    "action": "process",
    "data":   []any{1.0, 2.0, 3.0},
})

// 委托任务
taskID, err := agent.DelegateTask(ctx, "target-agent-id", "compute-task", map[string]any{
    "input": "some data",
})

// 发布事件
_, err = agent.Publish(ctx, "my.topic", map[string]any{
    "event": "something-happened",
})
```

### 订阅消息

```go
agent.Subscribe([]string{"my.topic", "other.topic"}, func(topic, publisherID string, payload map[string]any) {
    slog.Info("收到消息", "topic", topic, "from", publisherID)
})
```

## API 文档

### Config

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `RegistryHost` | string | `"localhost"` | Registry gRPC 地址 |
| `RegistryPort` | int | `9090` | Registry gRPC 端口 |
| `AgentID` | string | — | Agent 唯一标识 |
| `AgentName` | string | — | Agent 显示名称 |
| `AgentType` | string | — | Agent 类型（用于发现） |
| `AgentHost` | string | `"localhost"` | 本 Agent 对外暴露的 host |
| `AgentPort` | int | — | 本 Agent 对外暴露的 gRPC 端口 |
| `Capabilities` | []string | — | Agent 能力列表 |
| `JWTToken` | string | — | 认证 JWT Token |
| `ConnectTimeoutMs` | int | `5000` | 连接超时（毫秒） |
| `RequestTimeoutMs` | int | `30000` | 请求超时（毫秒） |
| `HeartbeatIntervalMs` | int | `10000` | 心跳间隔（毫秒） |
| `AutoRegister` | bool | `true` | 启动时自动注册 |

### Agent 方法

| 方法 | 说明 |
|------|------|
| `NewAgent(config)` | 创建 Agent 实例 |
| `OnRequest(handler)` | 设置同步请求处理器 |
| `OnMessage(handler)` | 设置单向消息处理器 |
| `OnTask(handler)` | 设置任务处理器 |
| `Start(ctx)` | 启动 Agent |
| `Close(ctx)` | 关闭 Agent |
| `Subscribe(topics, handler)` | 订阅 PubSub topics |
| `Discover(ctx, agentType)` | 发现指定类型的 Agent |
| `SendRequest(ctx, toAgentID, payload)` | 同步请求/响应 |
| `Send(ctx, toAgentID, topic, payload)` | 单向发送消息 |
| `Publish(ctx, topic, payload)` | 发布到 PubSub topic |
| `DelegateTask(ctx, toAgentID, taskType, input)` | 委托任务（60s 超时） |
| `DelegateTaskWithTimeout(...)` | 委托任务（自定义超时） |
| `GetTaskStatus(ctx, taskID)` | 查询任务状态 |
| `CancelTask(ctx, taskID, reason)` | 取消任务 |

### 处理器签名

```go
// 同步请求处理器
type RequestHandler func(fromAgentID, messageID string, payload map[string]any) (map[string]any, error)

// 单向消息处理器
type MessageHandler func(fromAgentID, topic string, payload map[string]any)

// 任务处理器
type TaskHandler func(taskID, taskType, delegatorAgentID string, input map[string]any) (map[string]any, error)

// PubSub 消息处理器
type PubSubHandler func(topic, publisherAgentID string, payload map[string]any)
```

## 示例

完整示例见 `examples/` 目录：

- `examples/agent_a/` — 数据处理 Agent（接收请求、执行任务、订阅事件）
- `examples/agent_b/` — 协调 Agent（发现、请求、委托任务、发布事件）

运行示例：

```bash
# 先启动 Registry（参考 a2a-framework 主项目）

# 启动 Agent A
cd examples/agent_a && go run main.go

# 启动 Agent B（另一个终端）
cd examples/agent_b && go run main.go
```

## 依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| `google.golang.org/grpc` | v1.62.0 | gRPC 通信 |
| `google.golang.org/protobuf` | v1.33.0 | Protocol Buffers |
| `github.com/golang-jwt/jwt/v5` | v5.2.1 | JWT 认证 |
| `github.com/google/uuid` | v1.6.0 | 唯一 ID 生成 |

## License

Apache 2.0
