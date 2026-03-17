package a2a

import "time"

// AgentStatus Agent 状态
type AgentStatus int32

const (
	AgentStatusUnknown  AgentStatus = 0
	AgentStatusOnline   AgentStatus = 1
	AgentStatusOffline  AgentStatus = 2
	AgentStatusBusy     AgentStatus = 3
	AgentStatusDraining AgentStatus = 4
)

// MessageType 消息类型
type MessageType int32

const (
	MessageTypeRequest      MessageType = 0
	MessageTypeResponse     MessageType = 1
	MessageTypeEvent        MessageType = 2
	MessageTypeTaskDelegate MessageType = 3
	MessageTypeTaskResult   MessageType = 4
	MessageTypeHeartbeat    MessageType = 5
	MessageTypeError        MessageType = 6
)

// Priority 消息优先级
type Priority int32

const (
	PriorityLow      Priority = 0
	PriorityNormal   Priority = 1
	PriorityHigh     Priority = 2
	PriorityCritical Priority = 3
)

// TaskStatus 任务状态
type TaskStatus int32

const (
	TaskStatusPending   TaskStatus = 0
	TaskStatusRunning   TaskStatus = 1
	TaskStatusSuccess   TaskStatus = 2
	TaskStatusFailed    TaskStatus = 3
	TaskStatusCancelled TaskStatus = 4
	TaskStatusTimeout   TaskStatus = 5
)

// AgentInfo Agent 元信息
type AgentInfo struct {
	AgentID       string            `json:"agent_id"`
	AgentName     string            `json:"agent_name"`
	AgentType     string            `json:"agent_type"`
	Host          string            `json:"host"`
	Port          int32             `json:"port"`
	Status        AgentStatus       `json:"status"`
	Capabilities  []string          `json:"capabilities"`
	Metadata      map[string]string `json:"metadata"`
	RegisteredAt  time.Time         `json:"registered_at"`
	LastHeartbeat time.Time         `json:"last_heartbeat"`
}

// Message A2A 消息
type Message struct {
	MessageID     string            `json:"message_id"`
	FromAgentID   string            `json:"from_agent_id"`
	ToAgentID     string            `json:"to_agent_id"`
	CorrelationID string            `json:"correlation_id"`
	Topic         string            `json:"topic"`
	Type          MessageType       `json:"type"`
	Payload       map[string]any    `json:"payload"`
	Headers       map[string]string `json:"headers"`
	CreatedAt     time.Time         `json:"created_at"`
	Priority      Priority          `json:"priority"`
}

// Task 任务
type Task struct {
	TaskID           string            `json:"task_id"`
	TaskType         string            `json:"task_type"`
	DelegatorAgentID string            `json:"delegator_agent_id"`
	ExecutorAgentID  string            `json:"executor_agent_id"`
	Input            map[string]any    `json:"input"`
	Output           map[string]any    `json:"output"`
	Status           TaskStatus        `json:"status"`
	TimeoutSeconds   int32             `json:"timeout_seconds"`
	Context          map[string]string `json:"context"`
}

// MessageHandler 消息处理器（单向）
type MessageHandler func(fromAgentID, topic string, payload map[string]any)

// RequestHandler 请求处理器（同步请求/响应）
type RequestHandler func(fromAgentID, messageID string, payload map[string]any) (map[string]any, error)

// TaskHandler 任务处理器
type TaskHandler func(taskID, taskType, delegatorAgentID string, input map[string]any) (map[string]any, error)

// PubSubHandler PubSub 消息处理器
type PubSubHandler func(topic, publisherAgentID string, payload map[string]any)
