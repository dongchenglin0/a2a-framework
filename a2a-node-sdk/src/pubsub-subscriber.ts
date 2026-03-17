/**
 * A2A Framework Node.js SDK - PubSubSubscriber
 * 实现发布订阅订阅端，支持流式接收和断线自动重连
 */

import { PubSubHandler } from './types';
import { createPubSubClient, buildMetadata } from './grpc-client';
import { structToObject, sleep, exponentialBackoff, formatError } from './utils';

// ==================== PubSubSubscriber 类 ====================

/**
 * 发布订阅订阅者
 * 建立 gRPC 流式连接，订阅指定 topic 的消息
 * 支持断线自动重连（指数退避）
 */
export class PubSubSubscriber {
  /** 注册中心主机 */
  private registryHost: string;
  /** 注册中心端口 */
  private registryPort: number;
  /** 当前 Agent ID */
  private agentId: string;
  /** JWT 令牌 */
  private jwtToken: string;
  /** 会话令牌 */
  private sessionToken: string;
  /** 连接超时 */
  private connectTimeoutMs: number;

  /** 当前订阅的 topics */
  private topics: string[] = [];
  /** 消息处理器 */
  private handler?: PubSubHandler;
  /** 当前 gRPC 流 */
  private currentStream: any = null;
  /** 是否主动取消订阅 */
  private cancelled: boolean = false;
  /** 重连次数 */
  private reconnectAttempt: number = 0;
  /** 最大重连次数（0 表示无限重连） */
  private maxReconnectAttempts: number = 0;
  /** 重连定时器 */
  private reconnectTimer?: NodeJS.Timeout;

  constructor(options: {
    registryHost: string;
    registryPort: number;
    agentId: string;
    jwtToken: string;
    sessionToken: string;
    connectTimeoutMs?: number;
    maxReconnectAttempts?: number;
  }) {
    this.registryHost = options.registryHost;
    this.registryPort = options.registryPort;
    this.agentId = options.agentId;
    this.jwtToken = options.jwtToken;
    this.sessionToken = options.sessionToken;
    this.connectTimeoutMs = options.connectTimeoutMs || 5000;
    this.maxReconnectAttempts = options.maxReconnectAttempts || 0;
  }

  // ==================== 订阅/取消订阅 ====================

  /**
   * 订阅指定 topics
   * 建立 gRPC 流式连接，收到消息时调用 handler
   * 断线后自动重连（指数退避）
   * @param topics 要订阅的 topic 列表
   * @param handler 消息处理器
   */
  subscribe(topics: string[], handler: PubSubHandler): void {
    if (this.currentStream) {
      console.warn('[PubSubSubscriber] 已有活跃订阅，请先调用 unsubscribe()');
      return;
    }

    this.topics = [...topics];
    this.handler = handler;
    this.cancelled = false;
    this.reconnectAttempt = 0;

    console.log(`[PubSubSubscriber] 开始订阅 topics: ${topics.join(', ')}`);
    this.connect();
  }

  /**
   * 取消订阅，关闭 gRPC 流
   */
  unsubscribe(): void {
    this.cancelled = true;

    // 取消重连定时器
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = undefined;
    }

    // 关闭当前流
    if (this.currentStream) {
      try {
        this.currentStream.cancel();
      } catch {
        // 忽略关闭错误
      }
      this.currentStream = null;
    }

    console.log('[PubSubSubscriber] 已取消订阅');
  }

  // ==================== 内部连接逻辑 ====================

  /**
   * 建立 gRPC 流式连接
   */
  private connect(): void {
    if (this.cancelled) return;

    try {
      const pubSubClient = createPubSubClient(
        this.registryHost,
        this.registryPort,
        this.connectTimeoutMs
      );

      const metadata = buildMetadata(this.jwtToken, this.sessionToken);

      const request = {
        topics: this.topics,
        subscriber_agent_id: this.agentId,
        session_token: this.sessionToken,
      };

      // 建立服务端流式调用
      const stream = pubSubClient.subscribe(request, metadata);
      this.currentStream = stream;

      // 重置重连计数
      this.reconnectAttempt = 0;

      console.log(`[PubSubSubscriber] gRPC 流已建立，等待消息...`);

      // 处理接收到的消息
      stream.on('data', (event: any) => {
        this.handleEvent(event);
      });

      // 处理流结束（服务端主动关闭）
      stream.on('end', () => {
        if (!this.cancelled) {
          console.log('[PubSubSubscriber] 服务端关闭了流，准备重连...');
          this.currentStream = null;
          this.scheduleReconnect();
        }
      });

      // 处理错误
      stream.on('error', (err: Error) => {
        if (!this.cancelled) {
          console.warn(`[PubSubSubscriber] 流发生错误: ${formatError(err)}`);
          this.currentStream = null;
          this.scheduleReconnect();
        }
      });

      // 处理流关闭
      stream.on('close', () => {
        if (!this.cancelled) {
          this.currentStream = null;
        }
      });
    } catch (err) {
      console.error(`[PubSubSubscriber] 建立连接失败: ${formatError(err)}`);
      if (!this.cancelled) {
        this.scheduleReconnect();
      }
    }
  }

  /**
   * 处理接收到的订阅事件
   * @param event gRPC 事件对象
   */
  private handleEvent(event: any): void {
    if (!this.handler) return;

    try {
      const topic = event.topic || '';
      const publisherAgentId = event.publisher_agent_id || event.publisherAgentId || '';
      const payload = structToObject(event.payload || {});

      this.handler(topic, publisherAgentId, payload);
    } catch (err) {
      console.error(`[PubSubSubscriber] 处理消息时发生错误: ${formatError(err)}`);
    }
  }

  /**
   * 安排重连（指数退避）
   */
  private scheduleReconnect(): void {
    if (this.cancelled) return;

    // 检查最大重连次数
    if (
      this.maxReconnectAttempts > 0 &&
      this.reconnectAttempt >= this.maxReconnectAttempts
    ) {
      console.error(
        `[PubSubSubscriber] 已达到最大重连次数 (${this.maxReconnectAttempts})，停止重连`
      );
      return;
    }

    const delayMs = exponentialBackoff(this.reconnectAttempt, 1000, 60000);
    this.reconnectAttempt++;

    console.log(
      `[PubSubSubscriber] 将在 ${Math.round(delayMs)}ms 后进行第 ${this.reconnectAttempt} 次重连...`
    );

    this.reconnectTimer = setTimeout(() => {
      if (!this.cancelled) {
        this.connect();
      }
    }, delayMs);

    // 允许进程在只有重连定时器时退出
    if (this.reconnectTimer.unref) {
      this.reconnectTimer.unref();
    }
  }

  /**
   * 更新会话令牌（注册后调用）
   * @param sessionToken 新的会话令牌
   */
  updateSessionToken(sessionToken: string): void {
    this.sessionToken = sessionToken;
  }

  /**
   * 是否处于活跃订阅状态
   */
  isActive(): boolean {
    return !this.cancelled && this.currentStream !== null;
  }

  /**
   * 获取当前订阅的 topics
   */
  getTopics(): string[] {
    return [...this.topics];
  }
}
