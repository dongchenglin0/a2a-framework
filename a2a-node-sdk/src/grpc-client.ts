/**
 * A2A Framework Node.js SDK - gRPC 客户端工厂
 * 使用 @grpc/grpc-js 和 @grpc/proto-loader 动态加载 proto 文件
 */

import * as grpc from '@grpc/grpc-js';
import * as protoLoader from '@grpc/proto-loader';
import path from 'path';

// ==================== Proto 加载 ====================

/**
 * proto 文件路径
 * 优先从环境变量 A2A_PROTO_PATH 读取，否则使用默认相对路径
 */
const PROTO_PATH =
  process.env.A2A_PROTO_PATH ||
  path.join(__dirname, '../../a2a-proto/src/main/proto/a2a.proto');

/** proto 加载选项 */
const PROTO_LOAD_OPTIONS: protoLoader.Options = {
  keepCase: true,        // 保持字段名大小写
  longs: String,         // long 类型转为 string
  enums: String,         // enum 转为 string
  defaults: true,        // 填充默认值
  oneofs: true,          // 支持 oneof
  includeDirs: [path.dirname(PROTO_PATH)],
};

/** 缓存已加载的 proto 包，避免重复加载 */
let cachedProto: any = null;

/**
 * 加载并缓存 proto 定义
 * @returns gRPC 包定义对象
 */
export function loadProto(): any {
  if (cachedProto) {
    return cachedProto;
  }

  try {
    const packageDefinition = protoLoader.loadSync(PROTO_PATH, PROTO_LOAD_OPTIONS);
    cachedProto = grpc.loadPackageDefinition(packageDefinition) as any;
    return cachedProto;
  } catch (err) {
    throw new Error(
      `加载 proto 文件失败: ${PROTO_PATH}\n` +
      `请确认文件存在，或通过环境变量 A2A_PROTO_PATH 指定正确路径\n` +
      `原始错误: ${(err as Error).message}`
    );
  }
}

/**
 * 重置 proto 缓存（主要用于测试）
 */
export function resetProtoCache(): void {
  cachedProto = null;
}

// ==================== gRPC 客户端选项 ====================

/**
 * 构建 gRPC 通道选项
 * @param connectTimeoutMs 连接超时（毫秒）
 */
function buildChannelOptions(connectTimeoutMs = 5000): grpc.ChannelOptions {
  return {
    'grpc.connect_timeout_ms': connectTimeoutMs,
    'grpc.keepalive_time_ms': 30000,
    'grpc.keepalive_timeout_ms': 10000,
    'grpc.keepalive_permit_without_calls': 1,
    'grpc.http2.max_pings_without_data': 0,
  };
}

// ==================== 客户端工厂函数 ====================

/**
 * 创建注册中心服务客户端
 * @param host 注册中心主机
 * @param port 注册中心端口
 * @param connectTimeoutMs 连接超时
 */
export function createRegistryClient(
  host: string,
  port: number,
  connectTimeoutMs = 5000
): any {
  const proto = loadProto();
  const RegistryService = proto['com.fox.a2a']?.RegistryService;
  if (!RegistryService) {
    throw new Error('proto 中未找到 RegistryService，请检查 proto 文件');
  }
  return new RegistryService(
    `${host}:${port}`,
    grpc.credentials.createInsecure(),
    buildChannelOptions(connectTimeoutMs)
  );
}

/**
 * 创建消息服务客户端
 * @param host 目标 Agent 主机
 * @param port 目标 Agent 端口
 * @param connectTimeoutMs 连接超时
 */
export function createMessagingClient(
  host: string,
  port: number,
  connectTimeoutMs = 5000
): any {
  const proto = loadProto();
  const MessagingService = proto['com.fox.a2a']?.MessagingService;
  if (!MessagingService) {
    throw new Error('proto 中未找到 MessagingService，请检查 proto 文件');
  }
  return new MessagingService(
    `${host}:${port}`,
    grpc.credentials.createInsecure(),
    buildChannelOptions(connectTimeoutMs)
  );
}

/**
 * 创建发布订阅服务客户端
 * @param host 注册中心主机
 * @param port 注册中心端口
 * @param connectTimeoutMs 连接超时
 */
export function createPubSubClient(
  host: string,
  port: number,
  connectTimeoutMs = 5000
): any {
  const proto = loadProto();
  const PubSubService = proto['com.fox.a2a']?.PubSubService;
  if (!PubSubService) {
    throw new Error('proto 中未找到 PubSubService，请检查 proto 文件');
  }
  return new PubSubService(
    `${host}:${port}`,
    grpc.credentials.createInsecure(),
    buildChannelOptions(connectTimeoutMs)
  );
}

/**
 * 创建任务服务客户端
 * @param host 目标 Agent 主机
 * @param port 目标 Agent 端口
 * @param connectTimeoutMs 连接超时
 */
export function createTaskClient(
  host: string,
  port: number,
  connectTimeoutMs = 5000
): any {
  const proto = loadProto();
  const TaskService = proto['com.fox.a2a']?.TaskService;
  if (!TaskService) {
    throw new Error('proto 中未找到 TaskService，请检查 proto 文件');
  }
  return new TaskService(
    `${host}:${port}`,
    grpc.credentials.createInsecure(),
    buildChannelOptions(connectTimeoutMs)
  );
}

/**
 * 构建带 JWT 认证的 gRPC 元数据
 * @param jwtToken JWT 令牌
 * @param sessionToken 会话令牌（可选）
 */
export function buildMetadata(jwtToken: string, sessionToken?: string): grpc.Metadata {
  const metadata = new grpc.Metadata();
  metadata.set('authorization', `Bearer ${jwtToken}`);
  if (sessionToken) {
    metadata.set('session-token', sessionToken);
  }
  return metadata;
}
