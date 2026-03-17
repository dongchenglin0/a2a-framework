package a2a

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"sync"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	pb "github.com/dongchenglin0/a2a-framework/a2a-go-sdk/proto"
)

// AgentServer Agent gRPC 服务器
type AgentServer struct {
	port           int
	grpcServer     *grpc.Server
	requestHandler RequestHandler
	messageHandler MessageHandler
	taskHandler    TaskHandler
	tasks          sync.Map // taskID -> *taskState
}

// taskState 任务运行状态
type taskState struct {
	task   *Task
	cancel context.CancelFunc
}

// NewAgentServer 创建 AgentServer
func NewAgentServer(port int) *AgentServer {
	return &AgentServer{port: port}
}

// OnRequest 设置同步请求处理器（链式调用）
func (s *AgentServer) OnRequest(h RequestHandler) *AgentServer {
	s.requestHandler = h
	return s
}

// OnMessage 设置单向消息处理器（链式调用）
func (s *AgentServer) OnMessage(h MessageHandler) *AgentServer {
	s.messageHandler = h
	return s
}

// OnTask 设置任务处理器（链式调用）
func (s *AgentServer) OnTask(h TaskHandler) *AgentServer {
	s.taskHandler = h
	return s
}

// Start 启动 gRPC 服务器（非阻塞，在 goroutine 中运行）
func (s *AgentServer) Start() error {
	lis, err := net.Listen("tcp", fmt.Sprintf(":%d", s.port))
	if err != nil {
		return fmt.Errorf("监听端口 %d 失败: %w", s.port, err)
	}
	s.grpcServer = grpc.NewServer()
	pb.RegisterMessagingServiceServer(s.grpcServer, &messagingServicer{server: s})
	pb.RegisterTaskServiceServer(s.grpcServer, &taskServicer{server: s})
	slog.Info("AgentServer 已启动", "port", s.port)
	go func() {
		if err := s.grpcServer.Serve(lis); err != nil {
			slog.Error("gRPC server 错误", "error", err)
		}
	}()
	return nil
}

// Stop 优雅停止服务器
func (s *AgentServer) Stop() {
	if s.grpcServer != nil {
		s.grpcServer.GracefulStop()
	}
}

// ─────────────────────────────────────────────
// messagingServicer 实现 MessagingService
// ─────────────────────────────────────────────

type messagingServicer struct {
	pb.UnimplementedMessagingServiceServer
	server *AgentServer
}

// SendRequest 处理同步请求，调用 requestHandler 并返回响应
func (m *messagingServicer) SendRequest(ctx context.Context, req *pb.SendRequestMsg) (*pb.SendResponseMsg, error) {
	if m.server.requestHandler == nil {
		return nil, status.Error(codes.Unimplemented, "未设置 requestHandler")
	}

	payload := StructToMap(req.Payload)
	result, err := m.server.requestHandler(req.FromAgentId, req.MessageId, payload)
	if err != nil {
		// 将业务错误封装为 gRPC 错误响应
		return &pb.SendResponseMsg{
			MessageId:     GenerateID(),
			CorrelationId: req.MessageId,
			FromAgentId:   req.ToAgentId,
			ToAgentId:     req.FromAgentId,
			Success:       false,
			ErrorMessage:  err.Error(),
			CreatedAt:     NowTimestamp(),
		}, nil
	}

	resultStruct, err := MapToStruct(result)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "序列化响应失败: %v", err)
	}

	return &pb.SendResponseMsg{
		MessageId:     GenerateID(),
		CorrelationId: req.MessageId,
		FromAgentId:   req.ToAgentId,
		ToAgentId:     req.FromAgentId,
		Payload:       resultStruct,
		Success:       true,
		CreatedAt:     NowTimestamp(),
	}, nil
}

// Send 处理单向消息，调用 messageHandler
func (m *messagingServicer) Send(ctx context.Context, req *pb.SendMsg) (*pb.SendAck, error) {
	if m.server.messageHandler != nil {
		payload := StructToMap(req.Payload)
		m.server.messageHandler(req.FromAgentId, req.Topic, payload)
	}
	return &pb.SendAck{
		MessageId: req.MessageId,
		Received:  true,
	}, nil
}

// Stream 双向流式传输（echo 模式，可按需扩展）
func (m *messagingServicer) Stream(stream pb.MessagingService_StreamServer) error {
	for {
		chunk, err := stream.Recv()
		if err != nil {
			return err
		}
		if err := stream.Send(chunk); err != nil {
			return err
		}
	}
}

// ─────────────────────────────────────────────
// taskServicer 实现 TaskService
// ─────────────────────────────────────────────

type taskServicer struct {
	pb.UnimplementedTaskServiceServer
	server *AgentServer
}

// DelegateTask 接受任务委托，立即返回 accepted=true，异步执行任务
func (t *taskServicer) DelegateTask(ctx context.Context, req *pb.DelegateTaskRequest) (*pb.DelegateTaskResponse, error) {
	if t.server.taskHandler == nil {
		return &pb.DelegateTaskResponse{
			TaskId:   req.TaskId,
			Accepted: false,
			Message:  "未设置 taskHandler",
		}, nil
	}

	// 构建任务对象
	task := &Task{
		TaskID:           req.TaskId,
		TaskType:         req.TaskType,
		DelegatorAgentID: req.DelegatorAgentId,
		ExecutorAgentID:  req.ExecutorAgentId,
		Input:            StructToMap(req.Input),
		Status:           TaskStatusPending,
		TimeoutSeconds:   req.TimeoutSeconds,
	}

	// 创建可取消的 context，用于支持 CancelTask
	taskCtx, cancel := context.WithCancel(context.Background())
	state := &taskState{task: task, cancel: cancel}
	t.server.tasks.Store(req.TaskId, state)

	// 异步执行任务
	go func() {
		defer func() {
			cancel()
			// 从 map 中移除已完成的任务（可选：保留一段时间供查询）
		}()

		// 更新状态为运行中
		task.Status = TaskStatusRunning

		// 执行任务处理器
		output, err := t.server.taskHandler(task.TaskID, task.TaskType, task.DelegatorAgentID, task.Input)
		if taskCtx.Err() != nil {
			// 任务已被取消
			task.Status = TaskStatusCancelled
			slog.Info("任务已取消", "taskId", task.TaskID)
			return
		}
		if err != nil {
			task.Status = TaskStatusFailed
			slog.Error("任务执行失败", "taskId", task.TaskID, "error", err)
		} else {
			task.Output = output
			task.Status = TaskStatusSuccess
			slog.Info("任务执行成功", "taskId", task.TaskID)
		}
	}()

	return &pb.DelegateTaskResponse{
		TaskId:   req.TaskId,
		Accepted: true,
		Message:  "任务已接受",
	}, nil
}

// GetTaskStatus 查询任务状态
func (t *taskServicer) GetTaskStatus(ctx context.Context, req *pb.GetTaskStatusRequest) (*pb.GetTaskStatusResponse, error) {
	val, ok := t.server.tasks.Load(req.TaskId)
	if !ok {
		return nil, status.Errorf(codes.NotFound, "任务 %s 不存在", req.TaskId)
	}
	state := val.(*taskState)
	task := state.task

	var outputStruct, inputStruct interface{ ProtoReflect() interface{} }
	_ = outputStruct
	_ = inputStruct

	inputPb, _ := MapToStruct(task.Input)
	outputPb, _ := MapToStruct(task.Output)

	return &pb.GetTaskStatusResponse{
		TaskId:           task.TaskID,
		TaskType:         task.TaskType,
		DelegatorAgentId: task.DelegatorAgentID,
		ExecutorAgentId:  task.ExecutorAgentID,
		Status:           pb.TaskStatus(task.Status),
		Input:            inputPb,
		Output:           outputPb,
		TimeoutSeconds:   task.TimeoutSeconds,
	}, nil
}

// CancelTask 取消任务
func (t *taskServicer) CancelTask(ctx context.Context, req *pb.CancelTaskRequest) (*pb.CancelTaskResponse, error) {
	val, ok := t.server.tasks.Load(req.TaskId)
	if !ok {
		return &pb.CancelTaskResponse{
			TaskId:  req.TaskId,
			Success: false,
			Message: fmt.Sprintf("任务 %s 不存在", req.TaskId),
		}, nil
	}
	state := val.(*taskState)
	// 调用 cancel 函数终止任务 goroutine
	state.cancel()
	state.task.Status = TaskStatusCancelled
	slog.Info("任务已请求取消", "taskId", req.TaskId, "reason", req.Reason)
	return &pb.CancelTaskResponse{
		TaskId:  req.TaskId,
		Success: true,
		Message: "取消请求已发送",
	}, nil
}
