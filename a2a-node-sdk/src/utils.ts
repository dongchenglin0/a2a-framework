/**
 * A2A Framework Node.js SDK - 工具函数
 */

import { v4 as uuidv4 } from 'uuid';

// ==================== gRPC 工具 ====================

/**
 * 将 gRPC callback 风格调用转换为 Promise
 * @param fn gRPC 客户端方法（已绑定 this）
 * @param request 请求对象
 * @param metadata 可选的 gRPC 元数据
 * @returns Promise<T>
 */
export function grpcCallToPromise<T>(
  fn: Function,
  request: any,
  metadata?: any
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const callback = (err: Error | null, response: T) => {
      if (err) {
        reject(err);
      } else {
        resolve(response);
      }
    };

    if (metadata) {
      fn(request, metadata, callback);
    } else {
      fn(request, callback);
    }
  });
}

/**
 * 带超时的 gRPC 调用
 * @param fn gRPC 客户端方法
 * @param request 请求对象
 * @param metadata gRPC 元数据
 * @param timeoutMs 超时毫秒数
 */
export function grpcCallWithTimeout<T>(
  fn: Function,
  request: any,
  metadata: any,
  timeoutMs: number
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    // 设置超时 deadline
    const deadline = new Date(Date.now() + timeoutMs);

    const callback = (err: Error | null, response: T) => {
      if (err) {
        reject(err);
      } else {
        resolve(response);
      }
    };

    fn(request, metadata, { deadline }, callback);
  });
}

// ==================== Protobuf Struct 转换 ====================

/**
 * 将 protobuf Struct 对象转换为普通 JS 对象
 * protobuf Struct 的 fields 是 { key: { kind: { stringValue/numberValue/... } } } 结构
 * @param struct protobuf Struct 对象
 * @returns 普通 JS 对象
 */
export function structToObject(struct: any): Record<string, any> {
  if (!struct) return {};

  // 如果已经是普通对象（proto-loader 有时会自动转换）
  if (typeof struct === 'object' && !struct.fields) {
    return struct as Record<string, any>;
  }

  const result: Record<string, any> = {};
  const fields = struct.fields || {};

  for (const key of Object.keys(fields)) {
    result[key] = valueToJs(fields[key]);
  }

  return result;
}

/**
 * 将 protobuf Value 转换为 JS 值
 * @param value protobuf Value 对象
 */
function valueToJs(value: any): any {
  if (!value) return null;

  // proto-loader 使用 kind oneof
  if (value.null_value !== undefined) return null;
  if (value.number_value !== undefined) return value.number_value;
  if (value.string_value !== undefined) return value.string_value;
  if (value.bool_value !== undefined) return value.bool_value;
  if (value.struct_value !== undefined) return structToObject(value.struct_value);
  if (value.list_value !== undefined) {
    const values = value.list_value.values || [];
    return values.map(valueToJs);
  }

  // 兼容直接值
  return value;
}

/**
 * 将普通 JS 对象转换为 protobuf Struct 格式
 * @param obj 普通 JS 对象
 * @returns protobuf Struct 对象
 */
export function objectToStruct(obj: Record<string, any>): any {
  if (!obj) return { fields: {} };

  const fields: Record<string, any> = {};

  for (const key of Object.keys(obj)) {
    fields[key] = jsToValue(obj[key]);
  }

  return { fields };
}

/**
 * 将 JS 值转换为 protobuf Value 格式
 * @param value JS 值
 */
function jsToValue(value: any): any {
  if (value === null || value === undefined) {
    return { null_value: 0 };
  }

  switch (typeof value) {
    case 'number':
      return { number_value: value };
    case 'string':
      return { string_value: value };
    case 'boolean':
      return { bool_value: value };
    case 'object':
      if (Array.isArray(value)) {
        return {
          list_value: {
            values: value.map(jsToValue),
          },
        };
      }
      return { struct_value: objectToStruct(value) };
    default:
      return { string_value: String(value) };
  }
}

// ==================== ID 生成 ====================

/**
 * 生成唯一消息 ID（UUID v4）
 * @returns UUID 字符串
 */
export function generateMessageId(): string {
  return uuidv4();
}

/**
 * 生成唯一任务 ID
 * @returns 带前缀的 UUID 字符串
 */
export function generateTaskId(): string {
  return `task-${uuidv4()}`;
}

// ==================== 异步工具 ====================

/**
 * 等待指定毫秒数
 * @param ms 等待毫秒数
 */
export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * 计算指数退避等待时间
 * @param attempt 当前重试次数（从 0 开始）
 * @param baseMs 基础等待时间（毫秒），默认 500ms
 * @param maxMs 最大等待时间（毫秒），默认 30000ms
 * @returns 等待毫秒数（含随机抖动）
 */
export function exponentialBackoff(
  attempt: number,
  baseMs = 500,
  maxMs = 30000
): number {
  // 指数退避: base * 2^attempt
  const exponential = baseMs * Math.pow(2, attempt);
  // 加入随机抖动（±20%），避免惊群效应
  const jitter = exponential * 0.2 * (Math.random() * 2 - 1);
  const delay = exponential + jitter;
  // 限制最大等待时间
  return Math.min(delay, maxMs);
}

// ==================== 网络工具 ====================

/**
 * 获取本机 IP 地址（非回环地址）
 * @returns IP 地址字符串，获取失败则返回 '127.0.0.1'
 */
export function getLocalIpAddress(): string {
  try {
    const os = require('os');
    const interfaces = os.networkInterfaces();

    for (const name of Object.keys(interfaces)) {
      const iface = interfaces[name];
      if (!iface) continue;

      for (const info of iface) {
        // 跳过回环地址和 IPv6
        if (info.family === 'IPv4' && !info.internal) {
          return info.address;
        }
      }
    }
  } catch {
    // 忽略错误
  }

  return '127.0.0.1';
}

/**
 * 格式化错误信息
 * @param err 错误对象
 */
export function formatError(err: unknown): string {
  if (err instanceof Error) {
    return err.message;
  }
  return String(err);
}
