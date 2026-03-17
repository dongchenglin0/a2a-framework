package a2a

import (
	"context"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"

	pb "github.com/dongchenglin0/a2a-framework/a2a-go-sdk/proto"
)

// Client A2A 客户端
type Client struct {
	config        Config
	registryConn  *grpc.ClientConn
	registryStub  pb.RegistryServiceClient
	pubsubStub    pb.PubSubServiceClient
	taskStub      pb.TaskServiceClient
	sessionToken  string
	agentConns    map[string]*grpc.ClientConn // 连接池：agentID -> conn
	agentConnsMu  sync.RWMutex
	heartbeatStop chan struct{}
}

// NewClient 创建新的 A2A 客户端
func NewClient(config Config) *Client {
	return &Client{
		config:        config,
		agentConns:    make(map[string]*grpc.ClientConn),
		heartbeatStop: make(chan struct{}),
	}
}

// Connect 连接到 Registry
func (c *Client) Connect(ctx context.Context) error {
	dialCtx, cancel := context.WithTimeout(ctx, time.Duration(c.config.ConnectTimeoutMs)*time.Millisecond)
	defer cancel()

	conn, err := grpc.DialContext(dialCtx,
		fmt.Sprintf("%s:%d", c.config.RegistryHost, c.config.RegistryPort),
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithBlock(),
	)
	if err != nil {
		return fmt.Errorf("连接 Registry 失败: %w", err)
	}
	c.registryConn = conn
	c.registryStub = pb.NewRegistryServiceClient(conn)
	c.pubsubStub = pb.NewPubSubServiceClient(conn)
	c.taskStub = pb.NewTaskServiceClient(conn)
	slog.Info("已连接到 Registry", "host", c.config.RegistryHost, "port", c.config.RegistryPort)
	return nil
}

// Register 注册到 Registry，保存返回的 sessionToken
func (c *Client) Register(ctx context.Context) error {
	// 构建 AgentInfo proto
	agentInfo := &pb.AgentInfo{
		AgentId:      c.config.AgentID,
		AgentName:    c.config.AgentName,
		AgentType:    c.config.AgentType,
		Host:         c.config.AgentHost,
		Port:         int32(c.config.AgentPort),
		Status:       pb.AgentStatus_ONLINE,
		Capabilities: c.config.Capabilities,
		Metadata:     c.config.Metadata,
	}

	// 如果配置了 JWT Token，附加到请求头
	reqCtx := ctx
	if c.config.JWTToken != "" {
		md := metadata.Pairs("authorization", "Bearer "+c.config.JWTToken)
		reqCtx = metadata.NewOutgoingContext(ctx, md)
	}

	resp, err := c.registryStub.Register(reqCtx, &pb.RegisterRequest{
		AgentInfo: agentInfo,
	})
	if err != nil {
		return fmt.Errorf("注册 Agent 失败: %w", err)
	}
	if !resp.Success {
		return fmt.Errorf("注册失败: %s", resp.Message)
	}
	c.sessionToken = resp.SessionToken
	slog.Info("Agent 注册成功", "agentId", c.config.AgentID, "sessionToken", c.sessionToken)
	return nil
}

// Deregister 注销 Agent
func (c *Client) Deregister(ctx context.Context) error {
	if c.registryStub == nil {
		return nil
	}
	resp, err := c.registryStub.Deregister(ctx, &pb.DeregisterRequest{
		AgentId:      c.config.AgentID,
		SessionToken: c.sessionToken,
	})
	if err != nil {
		return fmt.Errorf("注销 Agent 失败: %w", err)
	}
	if !resp.Success {
		return fmt.Errorf("注销失败: %s", resp.Message)
	}
	slog.Info("Agent 注销成功", "agentId", c.config.AgentID)
	return nil
}

// Discover 发现符合条件的 Agent 列表
func (c *Client) Discover(ctx context.Context, agentType string, capabilities []string) ([]AgentInfo, error) {
	resp, err := c.registryStub.Discover(ctx, &pb.DiscoverRequest{
		AgentType:    agentType,
		Capabilities: capabilities,
	})
	if err != nil {
		return nil, fmt.Errorf("发现 Agent 失败: %w", err)
	}

	agents := make([]AgentInfo, 0, len(resp.Agents))
	for _, a := range resp.Agents {
		agents = append(agents, protoToAgentInfo(a))
	}
	return agents, nil
}

// GetAgent 获取单个 Agent 信息
func (c *Client) GetAgent(ctx context.Context, agentID string) (*AgentInfo, error) {
	resp, err := c.registryStub.GetAgent(ctx, &pb.GetAgentRequest{
		AgentId: agentID,
	})
	if err != nil {
		return nil, fmt.Errorf("获取 Agent 失败: %w", err)
	}
	info := protoToAgentInfo(resp.AgentInfo)
	return &info, nil
}

// SendRequest 同步请求/响应：向目标 Agent 发送请求并等待响应
func (c *Client) SendRequest(ctx context.Context, toAgentID string, payload map[string]any) (map[string]any, error) {
	// 获取目标 Agent 的连接
	conn, err := c.getOrCreateAgentConn(ctx, toAgentID)
	if err != nil {
		return nil, fmt.Errorf("获取 Agent 连接失败: %w", err)
	}

	// 构建请求
	payloadStruct, err := MapToStruct(payload)
	if err != nil {
		return nil, fmt.Errorf("序列化 payload 失败: %w", err)
	}

	msgID := GenerateID()
	reqCtx, cancel := context.WithTimeout(ctx, time.Duration(c.config.RequestTimeoutMs)*time.Millisecond)
	defer cancel()

	stub := pb.NewMessagingServiceClient(conn)
	resp, err := stub.SendRequest(reqCtx, &pb.SendRequestMsg{
		MessageId:   msgID,
		FromAgentId: c.config.AgentID,
		ToAgentId:   toAgentID,
		Payload:     payloadStruct,
		CreatedAt:   NowTimestamp(),
	})
	if err != nil {
		return nil, fmt.Errorf("发送请求失败: %w", err)
	}

	return StructToMap(resp.Payload), nil
}

// Send 单向发送消息（fire-and-forget）
func (c *Client) Send(ctx context.Context, toAgentID, topic string, payload map[string]any) (string, error) {
	conn, err := c.getOrCreateAgentConn(ctx, toAgentID)
	if err != nil {
		return "", fmt.Errorf("获取 Agent 连接失败: %w", err)
	}

	payloadStruct, err := MapToStruct(payload)
	if err != nil {
		return "", fmt.Errorf("序列化 payload 失败: %w", err)
	}

	msgID := GenerateID()
	reqCtx, cancel := context.WithTimeout(ctx, time.Duration(c.config.RequestTimeoutMs)*time.Millisecond)
	defer cancel()

	stub := pb.NewMessagingServiceClient(conn)
	_, err = stub.Send(reqCtx, &pb.SendMsg{
		MessageId:   msgID,
		FromAgentId: c.config.AgentID,
		ToAgentId:   toAgentID,
		Topic:       topic,
		Payload:     payloadStruct,
		CreatedAt:   NowTimestamp(),
	})
	if err != nil {
		return "", fmt.Errorf("发送消息失败: %w", err)
	}
	return msgID, nil
}

// Publish 发布消息到 PubSub topic
func (c *Client) Publish(ctx context.Context, topic string, payload map[string]any) (string, error) {
	payloadStruct, err := MapToStruct(payload)
	if err != nil {
		return "", fmt.Errorf("序列化 payload 失败: %w", err)
	}

	msgID := GenerateID()
	reqCtx, cancel := context.WithTimeout(ctx, time.Duration(c.config.RequestTimeoutMs)*time.Millisecond)
	defer cancel()

	resp, err := c.pubsubStub.Publish(reqCtx, &pb.PubSubPublishRequest{
		MessageId:       msgID,
		PublisherAgentId: c.config.AgentID,
		Topic:           topic,
		Payload:         payloadStruct,
		SessionToken:    c.sessionToken,
	})
	if err != nil {
		return "", fmt.Errorf("发布消息失败: %w", err)
	}
	if !resp.Success {
		return "", fmt.Errorf("发布失败: %s", resp.Message)
	}
	return msgID, nil
}

// DelegateTask 委托任务给目标 Agent
func (c *Client) DelegateTask(ctx context.Context, toAgentID, taskType string, input map[string]any, timeoutSeconds int32) (string, error) {
	conn, err := c.getOrCreateAgentConn(ctx, toAgentID)
	if err != nil {
		return "", fmt.Errorf("获取 Agent 连接失败: %w", err)
	}

	inputStruct, err := MapToStruct(input)
	if err != nil {
		return "", fmt.Errorf("序列化 input 失败: %w", err)
	}

	taskID := GenerateID()
	reqCtx, cancel := context.WithTimeout(ctx, time.Duration(c.config.RequestTimeoutMs)*time.Millisecond)
	defer cancel()

	stub := pb.NewTaskServiceClient(conn)
	resp, err := stub.DelegateTask(reqCtx, &pb.DelegateTaskRequest{
		TaskId:           taskID,
		TaskType:         taskType,
		DelegatorAgentId: c.config.AgentID,
		ExecutorAgentId:  toAgentID,
		Input:            inputStruct,
		TimeoutSeconds:   timeoutSeconds,
	})
	if err != nil {
		return "", fmt.Errorf("委托任务失败: %w", err)
	}
	if !resp.Accepted {
		return "", fmt.Errorf("任务被拒绝: %s", resp.Message)
	}
	return resp.TaskId, nil
}

// GetTaskStatus 查询任务状态
func (c *Client) GetTaskStatus(ctx context.Context, taskID string) (*Task, error) {
	reqCtx, cancel := context.WithTimeout(ctx, time.Duration(c.config.RequestTimeoutMs)*time.Millisecond)
	defer cancel()

	resp, err := c.taskStub.GetTaskStatus(reqCtx, &pb.GetTaskStatusRequest{
		TaskId: taskID,
	})
	if err != nil {
		return nil, fmt.Errorf("查询任务状态失败: %w", err)
	}

	task := &Task{
		TaskID:           resp.TaskId,
		TaskType:         resp.TaskType,
		DelegatorAgentID: resp.DelegatorAgentId,
		ExecutorAgentID:  resp.ExecutorAgentId,
		Status:           TaskStatus(resp.Status),
		TimeoutSeconds:   resp.TimeoutSeconds,
		Input:            StructToMap(resp.Input),
		Output:           StructToMap(resp.Output),
	}
	return task, nil
}

// CancelTask 取消任务
func (c *Client) CancelTask(ctx context.Context, taskID, reason string) (bool, error) {
	reqCtx, cancel := context.WithTimeout(ctx, time.Duration(c.config.RequestTimeoutMs)*time.Millisecond)
	defer cancel()

	resp, err := c.taskStub.CancelTask(reqCtx, &pb.CancelTaskRequest{
		TaskId: taskID,
		Reason: reason,
	})
	if err != nil {
		return false, fmt.Errorf("取消任务失败: %w", err)
	}
	return resp.Success, nil
}

// StartHeartbeat 启动心跳 goroutine，定期向 Registry 发送心跳
func (c *Client) StartHeartbeat() {
	go func() {
		ticker := time.NewTicker(time.Duration(c.config.HeartbeatIntervalMs) * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
				_, err := c.registryStub.Heartbeat(ctx, &pb.HeartbeatRequest{
					AgentId:      c.config.AgentID,
					SessionToken: c.sessionToken,
					Status:       pb.AgentStatus_ONLINE,
				})
				cancel()
				if err != nil {
					slog.Warn("心跳失败", "error", err)
				}
			case <-c.heartbeatStop:
				return
			}
		}
	}()
}

// StopHeartbeat 停止心跳
func (c *Client) StopHeartbeat() {
	// 使用 select 防止重复关闭 channel
	select {
	case <-c.heartbeatStop:
		// 已经关闭，不重复操作
	default:
		close(c.heartbeatStop)
	}
}

// getOrCreateAgentConn 从连接池获取或新建到目标 Agent 的 gRPC 连接
func (c *Client) getOrCreateAgentConn(ctx context.Context, agentID string) (*grpc.ClientConn, error) {
	// 先尝试读锁
	c.agentConnsMu.RLock()
	conn, ok := c.agentConns[agentID]
	c.agentConnsMu.RUnlock()
	if ok {
		return conn, nil
	}

	// 需要新建连接，先查询 Agent 地址
	agentInfo, err := c.GetAgent(ctx, agentID)
	if err != nil {
		return nil, fmt.Errorf("查询 Agent[%s] 地址失败: %w", agentID, err)
	}

	addr := fmt.Sprintf("%s:%d", agentInfo.Host, agentInfo.Port)
	dialCtx, cancel := context.WithTimeout(ctx, time.Duration(c.config.ConnectTimeoutMs)*time.Millisecond)
	defer cancel()

	newConn, err := grpc.DialContext(dialCtx, addr,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithBlock(),
	)
	if err != nil {
		return nil, fmt.Errorf("连接 Agent[%s] 失败: %w", agentID, err)
	}

	// 写锁存入连接池
	c.agentConnsMu.Lock()
	// 双重检查，防止并发重复创建
	if existing, ok := c.agentConns[agentID]; ok {
		c.agentConnsMu.Unlock()
		newConn.Close()
		return existing, nil
	}
	c.agentConns[agentID] = newConn
	c.agentConnsMu.Unlock()

	slog.Info("已建立到 Agent 的连接", "agentId", agentID, "addr", addr)
	return newConn, nil
}

// Close 关闭所有连接并释放资源
func (c *Client) Close() {
	c.StopHeartbeat()
	if c.registryConn != nil {
		c.registryConn.Close()
	}
	c.agentConnsMu.Lock()
	defer c.agentConnsMu.Unlock()
	for id, conn := range c.agentConns {
		conn.Close()
		delete(c.agentConns, id)
	}
}

// protoToAgentInfo 将 protobuf AgentInfo 转换为 SDK AgentInfo
func protoToAgentInfo(a *pb.AgentInfo) AgentInfo {
	if a == nil {
		return AgentInfo{}
	}
	return AgentInfo{
		AgentID:       a.AgentId,
		AgentName:     a.AgentName,
		AgentType:     a.AgentType,
		Host:          a.Host,
		Port:          a.Port,
		Status:        AgentStatus(a.Status),
		Capabilities:  a.Capabilities,
		Metadata:      a.Metadata,
		RegisteredAt:  TimestampToTime(a.RegisteredAt),
		LastHeartbeat: TimestampToTime(a.LastHeartbeat),
	}
}
