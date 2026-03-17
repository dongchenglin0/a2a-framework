/**
 * A2A Framework Node.js SDK - A2AClient
 * 负责与注册中心通信、服务发现、消息发送、任务委托等
 */

import * as grpc from '@grpc/grpc-js';
import {
  A2AConfig,
  AgentInfo,
  AgentStatus,
  Task,
  TaskStatus,
  MessageType,
  Priority,
} from './types';
import {
  createRegistryClient,
  createMessagingClient,
  createTaskClient,
  createPubSubClient,
  buildMetadata,
} from './grpc-client';
import {
  grpcCallToPromise,
  grpcCallWithTimeout,
  generateMessageId,
  objectToStruct,
  structToObject,
  getLocalIpAddress,
  formatError,
} from './utils';

// ==================== 默认配置 ====================

const DEFAULT_CONFIG = {
  registryHost: 'localhost',
  registryPort: 9090,
  agentHost: '',
  agentPort: 0,
  capabilities: [] as string[],
  metadata: {} as Record<string, string>,
  connectTimeoutMs: 5000,
  requestTimeoutMs: 30000,
  heartbeatIntervalMs: 10000,
  autoRegister: true,
};

// ==================== A2AClient 类 ====================

/**
 * A2A 客户端
 * 提供 Agent 注册、服务发现、消息发送、任务委托等功能
 */
export class A2AClient {
  /** 完整配置（已填充默认值） */
  private config: Required<A2AConfig>;
  /** 注册中心 gRPC 客户端 */
  private registryClient: any;
  /** PubSub gRPC 客户端 */
  private pubSubClient: any;
  /** 会话令牌（注册成功后获取） */
  private sessionToken: string = '';
  /** 心跳定时器 */
  private heartbeatTimer?: NodeJS.Timeout;
  /** Agent 消息通道缓存: agentId -> messaging client */
  private agentChannels: Map<string, any> = new Map();
  /** Agent 信息缓存: agentId -> AgentInfo */
  private agentInfoCache: Map<string, AgentInfo> = new Map();
  /** 是否已注册 */
  private registered: boolean = false;

  constructor(config: A2AConfig) {
    // 合并默认配置
    this.config = {
      ...DEFAULT_CONFIG,
      ...config,
      agentHost: config.agentHost || getLocalIpAddress(),
      agentPort: config.agentPort || 0,
      capabilities: config.capabilities || [],
      metadata: config.metadata || {},
    } as Required<A2AConfig>;

    // 创建注册中心客户端
    this.registryClient = createRegistryClient(
      this.config.registryHost,
      this.config.registryPort,
      this.config.connectTimeoutMs
    );

    // 创建 PubSub 客户端（连接到注册中心）
    this.pubSubClient = createPubSubClient(
      this.config.registryHost,
      this.config.registryPort,
      this.config.connectTimeoutMs
    );
  }

  // ==================== 注册/注销 ====================

  /**
   * 向注册中心注册当前 Agent
   * 注册成功后自动启动心跳
   */
  async register(): Promise<void> {
    const agentInfo: AgentInfo = {
      agentId: this.config.agentId,
      agentName: this.config.agentName,
      agentType: this.config.agentType,
      host: this.config.agentHost,
      port: this.config.agentPort,
      metadata: this.config.metadata,
      status: AgentStatus.ONLINE,
      capabilities: this.config.capabilities,
    };

    const request = {
      agent_info: agentInfo,
      jwt_token: this.config.jwtToken,
    };

    const metadata = buildMetadata(this.config.jwtToken);

    try {
      const response = await grpcCallToPromise<any>(
        this.registryClient.register.bind(this.registryClient),
        request,
        metadata
      );

      if (!response.success) {
        throw new Error(`注册失败: ${response.message}`);
      }

      this.sessionToken = response.session_token || response.sessionToken || '';
      this.registered = true;

      console.log(
        `[A2AClient] Agent 注册成功: ${this.config.agentId}, sessionToken: ${this.sessionToken.substring(0, 8)}...`
      );

      // 启动心跳
      this.startHeartbeat();
    } catch (err) {
      throw new Error(`注册到注册中心失败: ${formatError(err)}`);
    }
  }

  /**
   * 从注册中心注销当前 Agent
   * 注销后停止心跳
   */
  async deregister(): Promise<void> {
    if (!this.registered) return;

    const request = {
      agent_id: this.config.agentId,
      session_token: this.sessionToken,
    };

    const metadata = buildMetadata(this.config.jwtToken, this.sessionToken);

    try {
      await grpcCallToPromise<any>(
        this.registryClient.deregister.bind(this.registryClient),
        request,
        metadata
      );
      this.registered = false;
      console.log(`[A2AClient] Agent 注销成功: ${this.config.agentId}`);
    } catch (err) {
      console.warn(`[A2AClient] 注销时发生错误（已忽略）: ${formatError(err)}`);
    } finally {
      this.stopHeartbeat();
    }
  }

  // ==================== 服务发现 ====================

  /**
   * 按类型发现 Agent 列表
   * @param agentType Agent 类型
   * @returns Agent 信息列表
   */
  async discover(agentType: string): Promise<AgentInfo[]> {
    const request = {
      agent_type: agentType,
      session_token: this.sessionToken,
    };

    const metadata = buildMetadata(this.config.jwtToken, this.sessionToken);

    try {
      const response = await grpcCallToPromise<any>(
        this.registryClient.discover.bind(this.registryClient),
        request,
        metadata
      );

      const agents: AgentInfo[] = (response.agents || []).map(this.mapAgentInfo);

      // 更新缓存
      for (const agent of agents) {
        this.agentInfoCache.set(agent.agentId, agent);
      }

      return agents;
    } catch (err) {
      throw new Error(`服务发现失败 (type=${agentType}): ${formatError(err)}`);
    }
  }

  /**
   * 按能力发现 Agent 列表
   * @param capability 能力名称
   * @returns Agent 信息列表
   */
  async discoverByCapability(capability: string): Promise<AgentInfo[]> {
    const request = {
      capability,
      session_token: this.sessionToken,
    };

    const metadata = buildMetadata(this.config.jwtToken, this.sessionToken);

    try {
      const response = await grpcCallToPromise<any>(
        this.registryClient.discoverByCapability.bind(this.registryClient),
        request,
        metadata
      );

      const agents: AgentInfo[] = (response.agents || []).map(this.mapAgentInfo);

      for (const agent of agents) {
        this.agentInfoCache.set(agent.agentId, agent);
      }

      return agents;
    } catch (err) {
      throw new Error(`按能力发现失败 (capability=${capability}): ${formatError(err)}`);
    }
  }

  /**
   * 获取指定 Agent 的信息
   * @param agentId Agent ID
   * @returns Agent 信息，不存在则返回 null
   */
  async getAgent(agentId: string): Promise<AgentInfo | null> {
    const request = {
      agent_id: agentId,
      session_token: this.sessionToken,
    };

    const metadata = buildMetadata(this.config.jwtToken, this.sessionToken);

    try {
      const response = await grpcCallToPromise<any>(
        this.registryClient.getAgent.bind(this.registryClient),
        request,
        metadata
      );

      if (!response.agent_info && !response.agentInfo) {
        return null;
      }

      const agentInfo = this.mapAgentInfo(response.agent_info || response.agentInfo);
      this.agentInfoCache.set(agentId, agentInfo);
      return agentInfo;
    } catch (err) {
      // 如果是 NOT_FOUND 错误，返回 null
      const grpcErr = err as any;
      if (grpcErr.code === grpc.status.NOT_FOUND) {
        return null;
      }
      throw new Error(`获取 Agent 信息失败 (agentId=${agentId}): ${formatError(err)}`);
    }
  }

  // ==================== 消息发送 ====================

  /**
   * 同步请求/响应
   * 先发现目标 Agent，建立连接，发送请求并等待响应
   * @param toAgentId 目标 Agent ID
   * @param payload 请求载荷
   * @param timeoutMs 超时毫秒数（默认使用配置值）
   * @returns 响应载荷
   */
  async sendRequest(
    toAgentId: string,
    payload: Record<string, any>,
    timeoutMs?: number
  ): Promise<Record<string, any>> {
    const timeout = timeoutMs || this.config.requestTimeoutMs;
    const messageId = generateMessageId();

    // 获取目标 Agent 的连接
    const messagingClient = await this.getOrCreateAgentChannel(toAgentId);

    const message = {
      message_id: messageId,
      from_agent_id: this.config.agentId,
      to_agent_id: toAgentId,
      type: MessageType.REQUEST,
      payload: objectToStruct(payload),
      headers: {},
      priority: Priority.NORMAL,
    };

    const request = {
      message,
      session_token: this.sessionToken,
    };

    const metadata = buildMetadata(this.config.jwtToken, this.sessionToken);

    try {
      const response = await grpcCallWithTimeout<any>(
        messagingClient.sendRequest.bind(messagingClient),
        request,
        metadata,
        timeout
      );

      return structToObject(response.payload || response.response_payload || {});
    } catch (err) {
      throw new Error(
        `发送请求失败 (to=${toAgentId}, msgId=${messageId}): ${formatError(err)}`
      );
    }
  }

  /**
   * 单向发送消息（fire-and-forget）
   * @param toAgentId 目标 Agent ID
   * @param topic 消息主题
   * @param payload 消息载荷
   * @returns 消息 ID
   */
  async send(
    toAgentId: string,
    topic: string,
    payload: Record<string, any>
  ): Promise<string> {
    const messageId = generateMessageId();

    const messagingClient = await this.getOrCreateAgentChannel(toAgentId);

    const message = {
      message_id: messageId,
      from_agent_id: this.config.agentId,
      to_agent_id: toAgentId,
      topic,
      type: MessageType.EVENT,
      payload: objectToStruct(payload),
      headers: {},
      priority: Priority.NORMAL,
    };

    const request = {
      message,
      session_token: this.sessionToken,
    };

    const metadata = buildMetadata(this.config.jwtToken, this.sessionToken);

    try {
      await grpcCallToPromise<any>(
        messagingClient.send.bind(messagingClient),
        request,
        metadata
      );
      return messageId;
    } catch (err) {
      throw new Error(
        `发送消息失败 (to=${toAgentId}, topic=${topic}): ${formatError(err)}`
      );
    }
  }

  // ==================== 发布订阅 ====================

  /**
   * 发布消息到指定 topic
   * @param topic 消息主题
   * @param payload 消息载荷
   * @returns 消息 ID
   */
  async publish(topic: string, payload: Record<string, any>): Promise<string> {
    const messageId = generateMessageId();

    const request = {
      topic,
      publisher_agent_id: this.config.agentId,
      payload: objectToStruct(payload),
      message_id: messageId,
      session_token: this.sessionToken,
    };

    const metadata = buildMetadata(this.config.jwtToken, this.sessionToken);

    try {
      await grpcCallToPromise<any>(
        this.pubSubClient.publish.bind(this.pubSubClient),
        request,
        metadata
      );
      return messageId;
    } catch (err) {
      throw new Error(`发布消息失败 (topic=${topic}): ${formatError(err)}`);
    }
  }

  // ==================== 任务委托 ====================

  /**
   * 委托任务给目标 Agent
   * @param toAgentId 目标 Agent ID
   * @param taskType 任务类型
   * @param input 任务输入
   * @param timeoutSeconds 任务超时（秒），默认 300
   * @returns 任务 ID
   */
  async delegateTask(
    toAgentId: string,
    taskType: string,
    input: Record<string, any>,
    timeoutSeconds = 300
  ): Promise<string> {
    const taskId = `task-${generateMessageId()}`;

    const taskClient = createTaskClient(
      await this.getAgentHost(toAgentId),
      await this.getAgentPort(toAgentId),
      this.config.connectTimeoutMs
    );

    const task = {
      task_id: taskId,
      task_type: taskType,
      delegator_agent_id: this.config.agentId,
      executor_agent_id: toAgentId,
      input: objectToStruct(input),
      status: TaskStatus.TASK_PENDING,
      timeout_seconds: timeoutSeconds,
    };

    const request = {
      task,
      session_token: this.sessionToken,
    };

    const metadata = buildMetadata(this.config.jwtToken, this.sessionToken);

    try {
      const response = await grpcCallToPromise<any>(
        taskClient.delegateTask.bind(taskClient),
        request,
        metadata
      );

      if (!response.accepted) {
        throw new Error(`任务被拒绝: ${response.message}`);
      }

      return response.task_id || taskId;
    } catch (err) {
      throw new Error(
        `委托任务失败 (to=${toAgentId}, type=${taskType}): ${formatError(err)}`
      );
    }
  }

  /**
   * 查询任务状态
   * @param taskId 任务 ID
   * @returns 任务信息
   */
  async getTaskStatus(taskId: string): Promise<Task> {
    const request = {
      task_id: taskId,
      session_token: this.sessionToken,
    };

    const metadata = buildMetadata(this.config.jwtToken, this.sessionToken);

    // 任务状态查询通过注册中心
    try {
      const response = await grpcCallToPromise<any>(
        this.registryClient.getTaskStatus.bind(this.registryClient),
        request,
        metadata
      );

      return this.mapTask(response.task || response);
    } catch (err) {
      throw new Error(`查询任务状态失败 (taskId=${taskId}): ${formatError(err)}`);
    }
  }

  /**
   * 取消任务
   * @param taskId 任务 ID
   * @param reason 取消原因
   * @returns 是否成功取消
   */
  async cancelTask(taskId: string, reason?: string): Promise<boolean> {
    const request = {
      task_id: taskId,
      reason: reason || '',
      session_token: this.sessionToken,
    };

    const metadata = buildMetadata(this.config.jwtToken, this.sessionToken);

    try {
      const response = await grpcCallToPromise<any>(
        this.registryClient.cancelTask.bind(this.registryClient),
        request,
        metadata
      );

      return response.success === true;
    } catch (err) {
      throw new Error(`取消任务失败 (taskId=${taskId}): ${formatError(err)}`);
    }
  }

  // ==================== 心跳 ====================

  /**
   * 启动心跳定时器
   * 定期向注册中心发送心跳，维持 Agent 在线状态
   */
  private startHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
    }

    this.heartbeatTimer = setInterval(async () => {
      try {
        const request = {
          agent_id: this.config.agentId,
          session_token: this.sessionToken,
          status: AgentStatus.ONLINE,
        };

        const metadata = buildMetadata(this.config.jwtToken, this.sessionToken);

        await grpcCallToPromise<any>(
          this.registryClient.heartbeat.bind(this.registryClient),
          request,
          metadata
        );
      } catch (err) {
        console.warn(`[A2AClient] 心跳发送失败: ${formatError(err)}`);
      }
    }, this.config.heartbeatIntervalMs);

    // 允许进程在只有心跳定时器时退出
    if (this.heartbeatTimer.unref) {
      this.heartbeatTimer.unref();
    }
  }

  /**
   * 停止心跳定时器
   */
  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = undefined;
    }
  }

  // ==================== 内部工具方法 ====================

  /**
   * 获取或创建目标 Agent 的消息通道
   * 先从缓存获取，缓存不存在则通过服务发现获取 Agent 地址并创建连接
   * @param agentId 目标 Agent ID
   * @returns MessagingService gRPC 客户端
   */
  private async getOrCreateAgentChannel(agentId: string): Promise<any> {
    // 从缓存获取
    if (this.agentChannels.has(agentId)) {
      return this.agentChannels.get(agentId);
    }

    // 通过服务发现获取 Agent 地址
    const agentInfo = await this.resolveAgent(agentId);

    // 创建消息通道
    const client = createMessagingClient(
      agentInfo.host,
      agentInfo.port,
      this.config.connectTimeoutMs
    );

    this.agentChannels.set(agentId, client);
    return client;
  }

  /**
   * 解析 Agent 信息（先查缓存，再查注册中心）
   * @param agentId Agent ID
   */
  private async resolveAgent(agentId: string): Promise<AgentInfo> {
    // 先查本地缓存
    const cached = this.agentInfoCache.get(agentId);
    if (cached && cached.status === AgentStatus.ONLINE) {
      return cached;
    }

    // 查询注册中心
    const agentInfo = await this.getAgent(agentId);
    if (!agentInfo) {
      throw new Error(`Agent 不存在或已下线: ${agentId}`);
    }

    return agentInfo;
  }

  /**
   * 获取目标 Agent 的主机地址
   */
  private async getAgentHost(agentId: string): Promise<string> {
    const info = await this.resolveAgent(agentId);
    return info.host;
  }

  /**
   * 获取目标 Agent 的端口
   */
  private async getAgentPort(agentId: string): Promise<number> {
    const info = await this.resolveAgent(agentId);
    return info.port;
  }

  /**
   * 将 gRPC 返回的 AgentInfo 映射为 TypeScript 类型
   */
  private mapAgentInfo(raw: any): AgentInfo {
    return {
      agentId: raw.agent_id || raw.agentId || '',
      agentName: raw.agent_name || raw.agentName || '',
      agentType: raw.agent_type || raw.agentType || '',
      host: raw.host || '',
      port: Number(raw.port) || 0,
      metadata: raw.metadata || {},
      status: raw.status as AgentStatus,
      capabilities: raw.capabilities || [],
    };
  }

  /**
   * 将 gRPC 返回的 Task 映射为 TypeScript 类型
   */
  private mapTask(raw: any): Task {
    return {
      taskId: raw.task_id || raw.taskId || '',
      taskType: raw.task_type || raw.taskType || '',
      delegatorAgentId: raw.delegator_agent_id || raw.delegatorAgentId || '',
      executorAgentId: raw.executor_agent_id || raw.executorAgentId || '',
      input: structToObject(raw.input || {}),
      output: raw.output ? structToObject(raw.output) : undefined,
      status: raw.status as TaskStatus,
      timeoutSeconds: Number(raw.timeout_seconds || raw.timeoutSeconds) || 300,
    };
  }

  /**
   * 清除 Agent 通道缓存（当 Agent 地址变更时使用）
   * @param agentId Agent ID，不传则清除所有
   */
  clearChannelCache(agentId?: string): void {
    if (agentId) {
      this.agentChannels.delete(agentId);
      this.agentInfoCache.delete(agentId);
    } else {
      this.agentChannels.clear();
      this.agentInfoCache.clear();
    }
  }

  /**
   * 关闭客户端，注销并停止心跳
   */
  close(): void {
    this.deregister().catch((err) => {
      console.warn(`[A2AClient] close 时注销失败: ${formatError(err)}`);
    });
    this.stopHeartbeat();
    this.agentChannels.clear();
  }

  /** 获取当前会话令牌 */
  getSessionToken(): string {
    return this.sessionToken;
  }

  /** 是否已注册 */
  isRegistered(): boolean {
    return this.registered;
  }
}
