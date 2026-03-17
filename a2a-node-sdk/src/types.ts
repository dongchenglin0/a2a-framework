/**
 * A2A Framework Node.js SDK - 类型定义
 * 对应 a2a.proto 中的所有消息类型和枚举
 */

// ==================== Agent 相关类型 ====================

/** Agent 信息 */
export interface AgentInfo {
  /** Agent 唯一标识 */
  agentId: string;
  /** Agent 名称 */
  agentName: string;
  /** Agent 类型（如 "llm-agent", "tool-agent" 等） */
  agentType: string;
  /** Agent 监听的主机地址 */
  host: string;
  /** Agent 监听的端口 */
  port: number;
  /** 自定义元数据 */
  metadata: Record<string, string>;
  /** Agent 当前状态 */
  status: AgentStatus;
  /** Agent 能力列表 */
  capabilities: string[];
}

/** Agent 状态枚举 */
export enum AgentStatus {
  UNKNOWN = 0,
  ONLINE = 1,
  OFFLINE = 2,
  BUSY = 3,
  DRAINING = 4,
}

// ==================== 消息相关类型 ====================

/** A2A 消息 */
export interface A2AMessage {
  /** 消息唯一 ID */
  messageId: string;
  /** 发送方 Agent ID */
  fromAgentId: string;
  /** 接收方 Agent ID */
  toAgentId: string;
  /** 关联 ID（用于请求/响应配对） */
  correlationId?: string;
  /** 消息主题 */
  topic?: string;
  /** 消息类型 */
  type: MessageType;
  /** 消息载荷 */
  payload: Record<string, any>;
  /** 消息头部 */
  headers: Record<string, string>;
  /** 创建时间 */
  createdAt: Date;
  /** 优先级 */
  priority: Priority;
}

/** 消息类型枚举 */
export enum MessageType {
  REQUEST = 0,
  RESPONSE = 1,
  EVENT = 2,
  TASK_DELEGATE = 3,
  TASK_RESULT = 4,
  HEARTBEAT = 5,
  ERROR = 6,
}

/** 消息优先级枚举 */
export enum Priority {
  LOW = 0,
  NORMAL = 1,
  HIGH = 2,
  CRITICAL = 3,
}

// ==================== 任务相关类型 ====================

/** 任务信息 */
export interface Task {
  /** 任务唯一 ID */
  taskId: string;
  /** 任务类型 */
  taskType: string;
  /** 委托方 Agent ID */
  delegatorAgentId: string;
  /** 执行方 Agent ID */
  executorAgentId: string;
  /** 任务输入参数 */
  input: Record<string, any>;
  /** 任务输出结果 */
  output?: Record<string, any>;
  /** 任务状态 */
  status: TaskStatus;
  /** 超时时间（秒） */
  timeoutSeconds: number;
}

/** 任务状态枚举 */
export enum TaskStatus {
  TASK_PENDING = 0,
  TASK_RUNNING = 1,
  TASK_SUCCESS = 2,
  TASK_FAILED = 3,
  TASK_CANCELLED = 4,
  TASK_TIMEOUT = 5,
}

// ==================== 配置类型 ====================

/** A2A SDK 配置 */
export interface A2AConfig {
  /** 注册中心主机地址，默认: 'localhost' */
  registryHost: string;
  /** 注册中心端口，默认: 9090 */
  registryPort: number;
  /** 当前 Agent 的唯一 ID */
  agentId: string;
  /** 当前 Agent 的名称 */
  agentName: string;
  /** 当前 Agent 的类型 */
  agentType: string;
  /** 当前 Agent 对外暴露的主机地址（可选，默认取本机 IP） */
  agentHost?: string;
  /** 当前 Agent 监听的端口（可选，默认随机端口） */
  agentPort?: number;
  /** 当前 Agent 的能力列表 */
  capabilities?: string[];
  /** 自定义元数据 */
  metadata?: Record<string, string>;
  /** JWT 认证令牌 */
  jwtToken: string;
  /** 连接超时（毫秒），默认: 5000 */
  connectTimeoutMs?: number;
  /** 请求超时（毫秒），默认: 30000 */
  requestTimeoutMs?: number;
  /** 心跳间隔（毫秒），默认: 10000 */
  heartbeatIntervalMs?: number;
  /** 是否自动注册到注册中心，默认: true */
  autoRegister?: boolean;
}

// ==================== 处理器类型 ====================

/**
 * 消息处理器 - 处理单向消息
 * @param fromAgentId 发送方 Agent ID
 * @param topic 消息主题
 * @param payload 消息载荷
 */
export type MessageHandler = (
  fromAgentId: string,
  topic: string,
  payload: Record<string, any>
) => void;

/**
 * 请求处理器 - 处理同步请求并返回响应
 * @param fromAgentId 发送方 Agent ID
 * @param messageId 消息 ID
 * @param payload 请求载荷
 * @returns 响应载荷
 */
export type RequestHandler = (
  fromAgentId: string,
  messageId: string,
  payload: Record<string, any>
) => Promise<Record<string, any>>;

/**
 * 任务处理器 - 处理委托任务并返回结果
 * @param taskId 任务 ID
 * @param taskType 任务类型
 * @param delegatorAgentId 委托方 Agent ID
 * @param input 任务输入
 * @returns 任务输出
 */
export type TaskHandler = (
  taskId: string,
  taskType: string,
  delegatorAgentId: string,
  input: Record<string, any>
) => Promise<Record<string, any>>;

/**
 * 发布订阅处理器 - 处理订阅的 topic 消息
 * @param topic 消息主题
 * @param publisherAgentId 发布方 Agent ID
 * @param payload 消息载荷
 */
export type PubSubHandler = (
  topic: string,
  publisherAgentId: string,
  payload: Record<string, any>
) => void;

// ==================== 内部类型 ====================

/** gRPC 元数据构建器 */
export interface GrpcMetadata {
  authorization?: string;
  [key: string]: string | undefined;
}

/** 注册请求 */
export interface RegisterRequest {
  agentInfo: AgentInfo;
  jwtToken: string;
}

/** 注册响应 */
export interface RegisterResponse {
  success: boolean;
  sessionToken: string;
  message: string;
}

/** 发现请求 */
export interface DiscoverRequest {
  agentType?: string;
  capability?: string;
  sessionToken: string;
}

/** 发现响应 */
export interface DiscoverResponse {
  agents: AgentInfo[];
}

/** 发送消息请求 */
export interface SendMessageRequest {
  message: A2AMessage;
  sessionToken: string;
}

/** 发送消息响应 */
export interface SendMessageResponse {
  messageId: string;
  accepted: boolean;
  message: string;
}

/** 发布请求 */
export interface PublishRequest {
  topic: string;
  publisherAgentId: string;
  payload: Record<string, any>;
  sessionToken: string;
}

/** 订阅请求 */
export interface SubscribeRequest {
  topics: string[];
  subscriberAgentId: string;
  sessionToken: string;
}

/** 订阅事件 */
export interface SubscribeEvent {
  topic: string;
  publisherAgentId: string;
  payload: Record<string, any>;
  eventId: string;
}

/** 委托任务请求 */
export interface DelegateTaskRequest {
  task: Task;
  sessionToken: string;
}

/** 委托任务响应 */
export interface DelegateTaskResponse {
  taskId: string;
  accepted: boolean;
  message: string;
}
