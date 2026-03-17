/**
 * A2A Framework Node.js SDK - AgentServer
 * 实现 gRPC 服务端，接收其他 Agent 发来的消息和任务
 */

import * as grpc from '@grpc/grpc-js';
import { MessageHandler, RequestHandler, TaskHandler } from './types';
import { loadProto } from './grpc-client';
import { structToObject, objectToStruct, generateMessageId, formatError } from './utils';

// ==================== AgentServer 类 ====================

/**
 * Agent gRPC 服务端
 * 监听指定端口，处理来自其他 Agent 的消息、请求和任务
 */
export class AgentServer {
  /** gRPC 服务器实例 */
  private server: grpc.Server;
  /** 监听端口 */
  private port: number;
  /** 请求处理器（同步请求/响应） */
  private requestHandler?: RequestHandler;
  /** 消息处理器（单向消息） */
  private messageHandler?: MessageHandler;
  /** 任务处理器（异步任务） */
  private taskHandler?: TaskHandler;
  /** 是否已启动 */
  private started: boolean = false;

  /**
   * @param port 监听端口，传 0 则随机分配
   */
  constructor(port: number) {
    this.port = port;
    this.server = new grpc.Server({
      'grpc.max_receive_message_length': 64 * 1024 * 1024, // 64MB
      'grpc.max_send_message_length': 64 * 1024 * 1024,    // 64MB
    });
  }

  // ==================== 处理器注册 ====================

  /**
   * 注册同步请求处理器
   * @param handler 请求处理函数，需返回响应载荷
   */
  onRequest(handler: RequestHandler): this {
    this.requestHandler = handler;
    return this;
  }

  /**
   * 注册单向消息处理器
   * @param handler 消息处理函数
   */
  onMessage(handler: MessageHandler): this {
    this.messageHandler = handler;
    return this;
  }

  /**
   * 注册任务处理器
   * @param handler 任务处理函数，需返回任务结果
   */
  onTask(handler: TaskHandler): this {
    this.taskHandler = handler;
    return this;
  }

  // ==================== 服务器生命周期 ====================

  /**
   * 启动 gRPC 服务器
   * 注册 MessagingService 和 TaskService 实现
   */
  async start(): Promise<void> {
    if (this.started) {
      throw new Error('AgentServer 已经启动');
    }

    // 加载 proto 定义
    const proto = loadProto();

    // 注册 MessagingService
    this.registerMessagingService(proto);

    // 注册 TaskService
    this.registerTaskService(proto);

    // 绑定端口并启动
    await new Promise<void>((resolve, reject) => {
      this.server.bindAsync(
        `0.0.0.0:${this.port}`,
        grpc.ServerCredentials.createInsecure(),
        (err, boundPort) => {
          if (err) {
            reject(new Error(`gRPC 服务器绑定端口失败: ${err.message}`));
            return;
          }
          this.port = boundPort; // 更新实际绑定的端口（当传入 0 时）
          resolve();
        }
      );
    });

    this.server.start();
    this.started = true;
    console.log(`[AgentServer] gRPC 服务器已启动，监听端口: ${this.port}`);
  }

  /**
   * 停止 gRPC 服务器
   */
  stop(): void {
    if (!this.started) return;

    this.server.tryShutdown((err) => {
      if (err) {
        console.warn(`[AgentServer] 优雅关闭失败，强制关闭: ${formatError(err)}`);
        this.server.forceShutdown();
      }
    });

    this.started = false;
    console.log('[AgentServer] gRPC 服务器已停止');
  }

  /**
   * 获取实际监听的端口（当构造时传入 0 时，启动后才能获取）
   */
  getPort(): number {
    return this.port;
  }

  // ==================== 服务实现注册 ====================

  /**
   * 注册 MessagingService 实现
   * 处理 sendRequest（同步请求）和 send（单向消息）
   */
  private registerMessagingService(proto: any): void {
    const MessagingService = proto['com.fox.a2a']?.MessagingService;
    if (!MessagingService) {
      console.warn('[AgentServer] proto 中未找到 MessagingService，跳过注册');
      return;
    }

    this.server.addService(MessagingService.service, {
      /**
       * 处理同步请求
       * 调用 requestHandler 并将结果作为响应返回
       */
      sendRequest: async (
        call: grpc.ServerUnaryCall<any, any>,
        callback: grpc.sendUnaryData<any>
      ) => {
        const request = call.request;
        const message = request.message || request;
        const messageId = message.message_id || message.messageId || generateMessageId();
        const fromAgentId = message.from_agent_id || message.fromAgentId || '';
        const payload = structToObject(message.payload || {});

        if (!this.requestHandler) {
          callback(null, {
            message_id: generateMessageId(),
            correlation_id: messageId,
            payload: objectToStruct({ error: '未注册请求处理器' }),
            success: false,
          });
          return;
        }

        try {
          const responsePayload = await this.requestHandler(fromAgentId, messageId, payload);
          callback(null, {
            message_id: generateMessageId(),
            correlation_id: messageId,
            payload: objectToStruct(responsePayload),
            success: true,
          });
        } catch (err) {
          console.error(`[AgentServer] 处理请求时发生错误: ${formatError(err)}`);
          callback(null, {
            message_id: generateMessageId(),
            correlation_id: messageId,
            payload: objectToStruct({ error: formatError(err) }),
            success: false,
          });
        }
      },

      /**
       * 处理单向消息
       * 调用 messageHandler，立即返回 accepted=true
       */
      send: async (
        call: grpc.ServerUnaryCall<any, any>,
        callback: grpc.sendUnaryData<any>
      ) => {
        const request = call.request;
        const message = request.message || request;
        const fromAgentId = message.from_agent_id || message.fromAgentId || '';
        const topic = message.topic || '';
        const payload = structToObject(message.payload || {});

        // 立即响应，异步处理消息
        callback(null, {
          message_id: message.message_id || message.messageId || generateMessageId(),
          accepted: true,
        });

        // 异步调用处理器
        if (this.messageHandler) {
          try {
            this.messageHandler(fromAgentId, topic, payload);
          } catch (err) {
            console.error(`[AgentServer] 处理消息时发生错误: ${formatError(err)}`);
          }
        }
      },
    });
  }

  /**
   * 注册 TaskService 实现
   * 处理 delegateTask（任务委托）
   */
  private registerTaskService(proto: any): void {
    const TaskService = proto['com.fox.a2a']?.TaskService;
    if (!TaskService) {
      console.warn('[AgentServer] proto 中未找到 TaskService，跳过注册');
      return;
    }

    this.server.addService(TaskService.service, {
      /**
       * 处理任务委托
       * 立即返回 accepted=true，异步执行任务
       */
      delegateTask: async (
        call: grpc.ServerUnaryCall<any, any>,
        callback: grpc.sendUnaryData<any>
      ) => {
        const request = call.request;
        const task = request.task || request;
        const taskId = task.task_id || task.taskId || generateMessageId();
        const taskType = task.task_type || task.taskType || '';
        const delegatorAgentId = task.delegator_agent_id || task.delegatorAgentId || '';
        const input = structToObject(task.input || {});

        // 立即返回接受响应
        callback(null, {
          task_id: taskId,
          accepted: true,
          message: '任务已接受，正在异步执行',
        });

        // 异步执行任务
        if (this.taskHandler) {
          this.executeTaskAsync(taskId, taskType, delegatorAgentId, input);
        } else {
          console.warn(`[AgentServer] 收到任务但未注册任务处理器: taskId=${taskId}`);
        }
      },

      /**
       * 查询任务状态（可选实现）
       */
      getTaskStatus: (
        call: grpc.ServerUnaryCall<any, any>,
        callback: grpc.sendUnaryData<any>
      ) => {
        // 简单实现：返回未知状态
        // 实际项目中应维护任务状态存储
        callback(null, {
          task: {
            task_id: call.request.task_id,
            status: 'TASK_RUNNING',
          },
        });
      },
    });
  }

  /**
   * 异步执行任务
   * @param taskId 任务 ID
   * @param taskType 任务类型
   * @param delegatorAgentId 委托方 Agent ID
   * @param input 任务输入
   */
  private async executeTaskAsync(
    taskId: string,
    taskType: string,
    delegatorAgentId: string,
    input: Record<string, any>
  ): Promise<void> {
    if (!this.taskHandler) return;

    try {
      console.log(`[AgentServer] 开始执行任务: taskId=${taskId}, type=${taskType}`);
      const output = await this.taskHandler(taskId, taskType, delegatorAgentId, input);
      console.log(`[AgentServer] 任务执行完成: taskId=${taskId}`);
      // TODO: 可以通过回调通知委托方任务完成
      void output;
    } catch (err) {
      console.error(`[AgentServer] 任务执行失败: taskId=${taskId}, error=${formatError(err)}`);
    }
  }
}
