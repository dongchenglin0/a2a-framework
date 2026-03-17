package com.fox.a2a.core;

import com.fox.a2a.core.handler.MessageHandler;
import com.fox.a2a.core.handler.TaskHandler;
import com.fox.a2a.core.pubsub.PubSubSubscriber;
import com.fox.a2a.proto.AgentInfo;
import com.fox.a2a.proto.Message;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A2A Agent 门面类
 * 整合 A2AClient（对外通信）和 AgentServer（接收消息），提供统一的 Agent 操作入口
 * 支持链式调用配置消息处理器、任务处理器和 topic 订阅
 */
@Slf4j
public class A2AAgent implements Closeable {

    /** Agent 配置 */
    private final A2AConfig config;

    /** 核心客户端（负责对外通信） */
    private final A2AClient client;

    /** gRPC 服务器（负责接收消息） */
    private final AgentServer server;

    /** PubSub 订阅者 */
    private PubSubSubscriber pubSubSubscriber;

    /** 待订阅的 topic 列表（在 start() 前收集） */
    private final List<String> pendingTopics = new ArrayList<>();

    /**
     * 私有构造函数，通过 create() 工厂方法创建实例
     *
     * @param config A2A 配置
     * @param client 核心客户端
     * @param server gRPC 服务器
     */
    private A2AAgent(A2AConfig config, A2AClient client, AgentServer server) {
        this.config = config;
        this.client = client;
        this.server = server;
    }

    /**
     * 工厂方法：根据配置创建 A2AAgent 实例
     *
     * @param config A2A 配置
     * @return A2AAgent 实例
     */
    public static A2AAgent create(A2AConfig config) {
        // 创建客户端
        A2AClient client = new A2AClient(config);
        // 创建服务器（handler 后续通过 onMessage/onTask 设置）
        AgentServer server = new AgentServer(config.getAgentPort());
        return new A2AAgent(config, client, server);
    }

    /**
     * 启动 Agent
     * 1. 启动 gRPC 服务器（开始监听端口）
     * 2. 如果配置了 autoRegister，向 Registry 注册
     * 3. 如果有待订阅的 topic，启动 PubSub 订阅
     *
     * @throws Exception 启动失败时抛出
     */
    public void start() throws Exception {
        log.info("正在启动 A2AAgent [{}]...", config.getAgentId());

        // 启动 gRPC 服务器
        server.start();
        log.info("AgentServer 已启动，端口: {}", config.getAgentPort());

        // 自动注册到 Registry
        if (config.isAutoRegister()) {
            client.register();
        }

        // 启动 PubSub 订阅（如果有待订阅的 topic）
        if (!pendingTopics.isEmpty()) {
            startPubSubSubscription(pendingTopics);
        }

        log.info("A2AAgent [{}] 启动完成", config.getAgentId());
    }

    /**
     * 设置消息处理器（链式调用）
     * 必须在 start() 之前调用
     *
     * @param handler 消息处理器实现
     * @return this（支持链式调用）
     */
    public A2AAgent onMessage(MessageHandler handler) {
        server.setMessageHandler(handler);
        log.debug("已设置 MessageHandler: {}", handler.getClass().getSimpleName());
        return this;
    }

    /**
     * 设置任务处理器（链式调用）
     * 必须在 start() 之前调用
     *
     * @param handler 任务处理器实现
     * @return this（支持链式调用）
     */
    public A2AAgent onTask(TaskHandler handler) {
        server.setTaskHandler(handler);
        log.debug("已设置 TaskHandler: {}", handler.getClass().getSimpleName());
        return this;
    }

    /**
     * 订阅指定 topic（链式调用）
     * 如果在 start() 之前调用，topic 会在 start() 时统一订阅
     * 如果在 start() 之后调用，立即建立订阅
     *
     * @param topics 要订阅的 topic 名称（可变参数）
     * @return this（支持链式调用）
     */
    public A2AAgent subscribe(String... topics) {
        List<String> topicList = Arrays.asList(topics);
        if (pubSubSubscriber != null && pubSubSubscriber.isRunning()) {
            // 已启动，直接追加订阅（重新订阅包含新 topic）
            pendingTopics.addAll(topicList);
            startPubSubSubscription(pendingTopics);
        } else {
            // 未启动，加入待订阅列表
            pendingTopics.addAll(topicList);
        }
        log.debug("已添加订阅 topics: {}", topicList);
        return this;
    }

    /**
     * 启动 PubSub 订阅
     *
     * @param topics 要订阅的 topic 列表
     */
    private void startPubSubSubscription(List<String> topics) {
        // 如果已有订阅，先取消
        if (pubSubSubscriber != null && pubSubSubscriber.isRunning()) {
            pubSubSubscriber.unsubscribe();
        }
        pubSubSubscriber = new PubSubSubscriber(
                client.getPubSubAsyncStub(),
                config.getAgentId(),
                client.getSessionToken()
        );
        pubSubSubscriber.subscribe(topics, message -> {
            log.debug("收到 PubSub 消息，topic: {}", message.getTopic());
            // PubSub 消息通过 MessageHandler 的 handleMessage 分发
        });
    }

    // ==================== 代理 A2AClient 的方法 ====================

    /**
     * 按类型发现 Agent
     *
     * @param agentType Agent 类型
     * @return 匹配的 AgentInfo 列表
     */
    public List<AgentInfo> discover(String agentType) {
        return client.discover(agentType);
    }

    /**
     * 按能力发现 Agent
     *
     * @param capability 能力名称
     * @return 匹配的 AgentInfo 列表
     */
    public List<AgentInfo> discoverByCapability(String capability) {
        return client.discoverByCapability(capability);
    }

    /**
     * 同步请求/响应
     *
     * @param toAgentId 目标 Agent ID
     * @param payload   请求载荷
     * @return 响应消息
     */
    public Message sendRequest(String toAgentId, Map<String, Object> payload) {
        return client.sendRequest(toAgentId, payload);
    }

    /**
     * 单向发送消息
     *
     * @param toAgentId 目标 Agent ID
     * @param topic     消息主题
     * @param payload   消息载荷
     * @return 消息 ID
     */
    public String send(String toAgentId, String topic, Map<String, Object> payload) {
        return client.send(toAgentId, topic, payload);
    }

    /**
     * 发布消息到 topic
     *
     * @param topic   主题名称
     * @param payload 消息载荷
     * @return 消息 ID
     */
    public String publish(String topic, Map<String, Object> payload) {
        return client.publish(topic, payload);
    }

    /**
     * 委托任务给目标 Agent
     *
     * @param toAgentId 目标 Agent ID
     * @param taskType  任务类型
     * @param input     任务输入
     * @return 任务 ID
     */
    public String delegateTask(String toAgentId, String taskType, Map<String, Object> input) {
        return client.delegateTask(toAgentId, taskType, input);
    }

    /**
     * 查询任务状态
     *
     * @param taskId 任务 ID
     * @return 任务信息
     */
    public com.fox.a2a.proto.Task getTaskStatus(String taskId) {
        return client.getTaskStatus(taskId);
    }

    /**
     * 取消任务
     *
     * @param taskId 任务 ID
     * @param reason 取消原因
     * @return 是否取消成功
     */
    public boolean cancelTask(String taskId, String reason) {
        return client.cancelTask(taskId, reason);
    }

    /**
     * 获取底层 A2AClient（高级用法）
     *
     * @return A2AClient
     */
    public A2AClient getClient() {
        return client;
    }

    /**
     * 获取底层 AgentServer（高级用法）
     *
     * @return AgentServer
     */
    public AgentServer getServer() {
        return server;
    }

    /**
     * 获取配置
     *
     * @return A2AConfig
     */
    public A2AConfig getConfig() {
        return config;
    }

    /**
     * 阻塞等待服务器终止（用于主线程保活）
     *
     * @throws InterruptedException 线程中断时抛出
     */
    public void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }

    /**
     * 关闭 Agent
     * 停止 PubSub 订阅、关闭客户端、停止服务器
     */
    @Override
    public void close() {
        log.info("正在关闭 A2AAgent [{}]...", config.getAgentId());
        // 停止 PubSub 订阅
        if (pubSubSubscriber != null && pubSubSubscriber.isRunning()) {
            pubSubSubscriber.unsubscribe();
        }
        // 关闭客户端（包含注销和停止心跳）
        client.close();
        // 停止 gRPC 服务器
        server.stop();
        log.info("A2AAgent [{}] 已关闭", config.getAgentId());
    }
}
