package a2a

import (
	"context"
	"log/slog"
)

// Agent A2A Agent 门面类，整合 Client（对外通信）和 Server（接收请求）
type Agent struct {
	config Config
	Client *Client
	Server *AgentServer
	pubsub *PubSubSubscriber
}

// NewAgent 创建新的 Agent 实例
func NewAgent(config Config) *Agent {
	return &Agent{
		config: config,
		Client: NewClient(config),
		Server: NewAgentServer(config.AgentPort),
	}
}

// OnRequest 设置同步请求处理器（链式调用）
func (a *Agent) OnRequest(h RequestHandler) *Agent {
	a.Server.OnRequest(h)
	return a
}

// OnMessage 设置单向消息处理器（链式调用）
func (a *Agent) OnMessage(h MessageHandler) *Agent {
	a.Server.OnMessage(h)
	return a
}

// OnTask 设置任务处理器（链式调用）
func (a *Agent) OnTask(h TaskHandler) *Agent {
	a.Server.OnTask(h)
	return a
}

// Start 启动 Agent：依次启动 gRPC Server、连接 Registry、注册、启动心跳
func (a *Agent) Start(ctx context.Context) error {
	// 1. 启动 gRPC Server（非阻塞）
	if err := a.Server.Start(); err != nil {
		return err
	}

	// 2. 连接 Registry
	if err := a.Client.Connect(ctx); err != nil {
		return err
	}

	// 3. 注册到 Registry
	if a.config.AutoRegister {
		if err := a.Client.Register(ctx); err != nil {
			return err
		}
	}

	// 4. 启动心跳 goroutine
	a.Client.StartHeartbeat()

	slog.Info("A2A Agent 已启动", "agentId", a.config.AgentID, "port", a.config.AgentPort)
	return nil
}

// Subscribe 订阅 PubSub topics，消息到达时调用 handler
func (a *Agent) Subscribe(topics []string, handler PubSubHandler) *Agent {
	a.pubsub = NewPubSubSubscriber(
		a.Client.pubsubStub,
		a.config.AgentID,
		a.Client.sessionToken,
	)
	a.pubsub.Subscribe(topics, handler)
	return a
}

// Close 优雅关闭 Agent：取消订阅、注销、关闭连接、停止服务器
func (a *Agent) Close(ctx context.Context) {
	if a.pubsub != nil {
		a.pubsub.Unsubscribe()
	}
	_ = a.Client.Deregister(ctx)
	a.Client.Close()
	a.Server.Stop()
	slog.Info("A2A Agent 已关闭", "agentId", a.config.AgentID)
}

// ─────────────────────────────────────────────
// 代理 Client 方法，方便直接通过 Agent 调用
// ─────────────────────────────────────────────

// Discover 发现指定类型的 Agent 列表
func (a *Agent) Discover(ctx context.Context, agentType string) ([]AgentInfo, error) {
	return a.Client.Discover(ctx, agentType, nil)
}

// DiscoverWithCapabilities 发现具有指定能力的 Agent 列表
func (a *Agent) DiscoverWithCapabilities(ctx context.Context, agentType string, capabilities []string) ([]AgentInfo, error) {
	return a.Client.Discover(ctx, agentType, capabilities)
}

// SendRequest 向目标 Agent 发送同步请求并等待响应
func (a *Agent) SendRequest(ctx context.Context, toAgentID string, payload map[string]any) (map[string]any, error) {
	return a.Client.SendRequest(ctx, toAgentID, payload)
}

// Send 向目标 Agent 单向发送消息（fire-and-forget）
func (a *Agent) Send(ctx context.Context, toAgentID, topic string, payload map[string]any) (string, error) {
	return a.Client.Send(ctx, toAgentID, topic, payload)
}

// Publish 发布消息到 PubSub topic
func (a *Agent) Publish(ctx context.Context, topic string, payload map[string]any) (string, error) {
	return a.Client.Publish(ctx, topic, payload)
}

// DelegateTask 委托任务给目标 Agent（默认超时 60 秒）
func (a *Agent) DelegateTask(ctx context.Context, toAgentID, taskType string, input map[string]any) (string, error) {
	return a.Client.DelegateTask(ctx, toAgentID, taskType, input, 60)
}

// DelegateTaskWithTimeout 委托任务给目标 Agent（自定义超时）
func (a *Agent) DelegateTaskWithTimeout(ctx context.Context, toAgentID, taskType string, input map[string]any, timeoutSeconds int32) (string, error) {
	return a.Client.DelegateTask(ctx, toAgentID, taskType, input, timeoutSeconds)
}

// GetTaskStatus 查询任务状态
func (a *Agent) GetTaskStatus(ctx context.Context, taskID string) (*Task, error) {
	return a.Client.GetTaskStatus(ctx, taskID)
}

// CancelTask 取消任务
func (a *Agent) CancelTask(ctx context.Context, taskID, reason string) (bool, error) {
	return a.Client.CancelTask(ctx, taskID, reason)
}
