/**
 * A2A Framework Node.js SDK - A2AAgent
 * 门面类，整合 A2AClient（客户端）和 AgentServer（服务端）
 * 提供统一的 Agent 生命周期管理和消息处理接口
 */

import {
  A2AConfig,
  AgentInfo,
  Task,
  MessageHandler,
  RequestHandler,
  TaskHandler,
  PubSubHandler,
} from './types';
import { A2AClient } from './a2a-client';
import { AgentServer } from './a2a-server';
import { PubSubSubscriber } from './pubsub-subscriber';
import { formatError } from './utils';

// ==================== A2AAgent 类 ====================

/**
 * A2A Agent 门面类
 * 整合客户端（服务发现、消息发送）和服务端（接收消息）功能
 * 提供简洁的 API 用于构建 A2A 应用
 *
 * @example
 * ```typescript
 * const agent = A2AAgent.create({
 *   agentId: 'my-agent-001',
 *   agentName: 'My Agent',
 *   agentType: 'llm-agent',
 *   agentPort: 8080,
 *   registryHost: 'localhost',
 *   registryPort: 9090,
 *   jwtToken: 'your-jwt-token',
 * });
 *
 * agent.onRequest(async (fromAgentId, messageId, payload) => {
 *   return { result: 'ok' };
 * });
 *
 * await agent.start();
 * ```
 */
export class A2AAgent {
  /** A2A 客户端（负责对外通信） */
  private client: A2AClient;
  /** Agent gRPC 服务端（负责接收消息） */
  private server: AgentServer;
  /** PubSub 订阅者列表 */
  private subscribers: PubSubSubscriber[] = [];
  /** 配置 */
  private config: A2AConfig;
  /** 是否已启动 */
  private started: boolean = false;

  /**
   * 私有构造函数，请使用 A2AAgent.create() 创建实例
   */
  private constructor(config: A2AConfig) {
    this.config = config;

    // 创建客户端
    this.client = new A2AClient(config);

    // 创建服务端（监听指定端口，0 表示随机端口）
    this.server = new AgentServer(config.agentPort || 0);
  }

  // ==================== 工厂方法 ====================

  /**
   * 创建 A2AAgent 实例
   * @param config A2A 配置
   */
  static create(config: A2AConfig): A2AAgent {
    return new A2AAgent(config);
  }

  // ==================== 生命周期 ====================

  /**
   * 启动 Agent
   * 1. 启动 gRPC 服务端
   * 2. 向注册中心注册（如果 autoRegister=true）
   */
  async start(): Promise<void> {
    if (this.started) {
      throw new Error('A2AAgent 已经启动');
    }

    try {
      // 1. 启动 gRPC 服务端
      await this.server.start();

      // 获取实际绑定的端口（当配置为 0 时）
      const actualPort = this.server.getPort();
      if (actualPort !== this.config.agentPort) {
        console.log(`[A2AAgent] 服务端实际端口: ${actualPort}`);
        // 更新配置中的端口，注册时使用实际端口
        (this.config as any).agentPort = actualPort;
      }

      // 2. 向注册中心注册
      if (this.config.autoRegister !== false) {
        await this.client.register();
      }

      this.started = true;
      console.log(
        `[A2AAgent] Agent 已启动: ${this.config.agentId} (port=${actualPort})`
      );
    } catch (err) {
      // 启动失败时清理资源
      this.server.stop();
      throw new Error(`A2AAgent 启动失败: ${formatError(err)}`);
    }
  }

  /**
   * 关闭 Agent
   * 1. 从注册中心注销
   * 2. 取消所有 PubSub 订阅
   * 3. 停止 gRPC 服务端
   */
  close(): void {
    // 取消所有订阅
    for (const subscriber of this.subscribers) {
      subscriber.unsubscribe();
    }
    this.subscribers = [];

    // 关闭客户端（注销 + 停止心跳）
    this.client.close();

    // 停止服务端
    this.server.stop();

    this.started = false;
    console.log(`[A2AAgent] Agent 已关闭: ${this.config.agentId}`);
  }

  // ==================== 处理器注册 ====================

  /**
   * 注册同步请求处理器
   * 当其他 Agent 调用 sendRequest 时触发
   * @param handler 请求处理函数
   */
  onRequest(handler: RequestHandler): this {
    this.server.onRequest(handler);
    return this;
  }

  /**
   * 注册单向消息处理器
   * 当其他 Agent 调用 send 时触发
   * @param handler 消息处理函数
   */
  onMessage(handler: MessageHandler): this {
    this.server.onMessage(handler);
    return this;
  }

  /**
   * 注册任务处理器
   * 当其他 Agent 调用 delegateTask 时触发
   * @param handler 任务处理函数
   */
  onTask(handler: TaskHandler): this {
    this.server.onTask(handler);
    return this;
  }

  /**
   * 订阅 PubSub topics
   * @param topics 要订阅的 topic 列表
   * @param handler 消息处理器
   */
  subscribe(topics: string[], handler: PubSubHandler): this {
    const subscriber = new PubSubSubscriber({
      registryHost: this.config.registryHost,
      registryPort: this.config.registryPort,
      agentId: this.config.agentId,
      jwtToken: this.config.jwtToken,
      sessionToken: this.client.getSessionToken(),
      connectTimeoutMs: this.config.connectTimeoutMs,
    });

    subscriber.subscribe(topics, handler);
    this.subscribers.push(subscriber);
    return this;
  }

  // ==================== 客户端方法代理 ====================

  /**
   * 按类型发现 Agent 列表
   * @param agentType Agent 类型
   */
  discover(agentType: string): Promise<AgentInfo[]> {
    return this.client.discover(agentType);
  }

  /**
   * 按能力发现 Agent 列表
   * @param capability 能力名称
   */
  discoverByCapability(capability: string): Promise<AgentInfo[]> {
    return this.client.discoverByCapability(capability);
  }

  /**
   * 获取指定 Agent 信息
   * @param agentId Agent ID
   */
  getAgent(agentId: string): Promise<AgentInfo | null> {
    return this.client.getAgent(agentId);
  }

  /**
   * 发送同步请求并等待响应
   * @param toAgentId 目标 Agent ID
   * @param payload 请求载荷
   * @param timeoutMs 超时毫秒数
   */
  sendRequest(
    toAgentId: string,
    payload: Record<string, any>,
    timeoutMs?: number
  ): Promise<Record<string, any>> {
    return this.client.sendRequest(toAgentId, payload, timeoutMs);
  }

  /**
   * 发送单向消息（fire-and-forget）
   * @param toAgentId 目标 Agent ID
   * @param topic 消息主题
   * @param payload 消息载荷
   */
  send(
    toAgentId: string,
    topic: string,
    payload: Record<string, any>
  ): Promise<string> {
    return this.client.send(toAgentId, topic, payload);
  }

  /**
   * 发布消息到 topic
   * @param topic 消息主题
   * @param payload 消息载荷
   */
  publish(topic: string, payload: Record<string, any>): Promise<string> {
    return this.client.publish(topic, payload);
  }

  /**
   * 委托任务给目标 Agent
   * @param toAgentId 目标 Agent ID
   * @param taskType 任务类型
   * @param input 任务输入
   * @param timeoutSeconds 任务超时（秒）
   */
  delegateTask(
    toAgentId: string,
    taskType: string,
    input: Record<string, any>,
    timeoutSeconds?: number
  ): Promise<string> {
    return this.client.delegateTask(toAgentId, taskType, input, timeoutSeconds);
  }

  /**
   * 查询任务状态
   * @param taskId 任务 ID
   */
  getTaskStatus(taskId: string): Promise<Task> {
    return this.client.getTaskStatus(taskId);
  }

  /**
   * 取消任务
   * @param taskId 任务 ID
   * @param reason 取消原因
   */
  cancelTask(taskId: string, reason?: string): Promise<boolean> {
    return this.client.cancelTask(taskId, reason);
  }

  // ==================== 状态查询 ====================

  /**
   * 是否已启动
   */
  isStarted(): boolean {
    return this.started;
  }

  /**
   * 获取 Agent ID
   */
  getAgentId(): string {
    return this.config.agentId;
  }

  /**
   * 获取服务端监听端口
   */
  getPort(): number {
    return this.server.getPort();
  }

  /**
   * 获取底层 A2AClient（用于高级操作）
   */
  getClient(): A2AClient {
    return this.client;
  }

  /**
   * 获取底层 AgentServer（用于高级操作）
   */
  getServer(): AgentServer {
    return this.server;
  }
}
