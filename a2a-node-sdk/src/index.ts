/**
 * A2A Framework Node.js SDK - 公共 API 入口
 * 导出所有公共类、类型和工具函数
 */

// ==================== 核心类 ====================

/** A2A Agent 门面类（推荐使用） */
export { A2AAgent } from './a2a-agent';

/** A2A 客户端（服务发现、消息发送、任务委托） */
export { A2AClient } from './a2a-client';

/** Agent gRPC 服务端（接收消息和任务） */
export { AgentServer } from './a2a-server';

/** PubSub 订阅者（订阅 topic 消息） */
export { PubSubSubscriber } from './pubsub-subscriber';

// ==================== 类型定义 ====================

export * from './types';

// ==================== gRPC 工具（高级用法） ====================

export {
  loadProto,
  resetProtoCache,
  createRegistryClient,
  createMessagingClient,
  createPubSubClient,
  createTaskClient,
  buildMetadata,
} from './grpc-client';

// ==================== 工具函数 ====================

export {
  grpcCallToPromise,
  grpcCallWithTimeout,
  structToObject,
  objectToStruct,
  generateMessageId,
  generateTaskId,
  sleep,
  exponentialBackoff,
  getLocalIpAddress,
  formatError,
} from './utils';
