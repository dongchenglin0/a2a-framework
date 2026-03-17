package com.fox.a2a.core;

import com.fox.a2a.proto.*;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.util.*;
import java.util.concurrent.*;

/**
 * A2A 核心客户端
 * 负责与 Registry 通信，实现 Agent 注册、发现、消息发送、任务委托等功能
 */
@Slf4j
public class A2AClient implements Closeable {

    /** 客户端配置 */
    private final A2AConfig config;

    /** 连接 Registry 的 gRPC Channel */
    private ManagedChannel registryChannel;

    /** Registry 同步 Stub */
    private RegistryServiceGrpc.RegistryServiceBlockingStub registryStub;

    /** Registry 异步 Stub */
    private RegistryServiceGrpc.RegistryServiceStub registryAsyncStub;

    /** 消息服务同步 Stub（连接到 Registry 的消息路由） */
    private MessagingServiceGrpc.MessagingServiceBlockingStub messagingStub;

    /** PubSub 服务同步 Stub */
    private PubSubServiceGrpc.PubSubServiceBlockingStub pubSubStub;

    /** 任务服务同步 Stub */
    private TaskServiceGrpc.TaskServiceBlockingStub taskStub;

    /** 注册后获得的会话 token */
    private volatile String sessionToken;

    /** 心跳定时执行器 */
    private ScheduledExecutorService heartbeatExecutor;

    /** 到各 Agent 的 Channel 连接池，key 为 "host:port" */
    private final ConcurrentHashMap<String, ManagedChannel> channelPool = new ConcurrentHashMap<>();

    /**
     * 构造函数，初始化 gRPC Channel 和各服务 Stub
     *
     * @param config A2A 配置
     */
    public A2AClient(A2AConfig config) {
        this.config = config;
        // 建立到 Registry 的 gRPC 连接（明文，生产环境可改为 TLS）
        this.registryChannel = ManagedChannelBuilder
                .forAddress(config.getRegistryHost(), config.getRegistryPort())
                .usePlaintext()
                .build();

        // 初始化各服务 Stub
        this.registryStub = RegistryServiceGrpc.newBlockingStub(registryChannel);
        this.registryAsyncStub = RegistryServiceGrpc.newStub(registryChannel);
        this.messagingStub = MessagingServiceGrpc.newBlockingStub(registryChannel);
        this.pubSubStub = PubSubServiceGrpc.newBlockingStub(registryChannel);
        this.taskStub = TaskServiceGrpc.newBlockingStub(registryChannel);

        log.info("A2AClient 初始化完成，Registry 地址: {}:{}", config.getRegistryHost(), config.getRegistryPort());
    }

    /**
     * 注册本 Agent 到 Registry
     * 注册成功后启动心跳
     */
    public void register() {
        log.info("正在注册 Agent [{}] 到 Registry...", config.getAgentId());

        // 构建注册请求
        RegisterRequest.Builder requestBuilder = RegisterRequest.newBuilder()
                .setAgentId(config.getAgentId())
                .setAgentName(config.getAgentName())
                .setAgentType(config.getAgentType())
                .setHost(config.getAgentHost())
                .setPort(config.getAgentPort());

        // 添加能力列表
        if (config.getCapabilities() != null) {
            requestBuilder.addAllCapabilities(config.getCapabilities());
        }

        // 添加元数据
        if (config.getMetadata() != null) {
            requestBuilder.putAllMetadata(config.getMetadata());
        }

        // 添加 JWT token（如果有）
        if (config.getJwtToken() != null && !config.getJwtToken().isEmpty()) {
            requestBuilder.setJwtToken(config.getJwtToken());
        }

        try {
            // 调用 Registry 注册接口
            RegisterResponse response = registryStub.register(requestBuilder.build());
            if (response.getSuccess()) {
                this.sessionToken = response.getSessionToken();
                log.info("Agent [{}] 注册成功，sessionToken: {}", config.getAgentId(), sessionToken);
                // 注册成功后启动心跳
                startHeartbeat();
            } else {
                throw new RuntimeException("Agent 注册失败: " + response.getMessage());
            }
        } catch (Exception e) {
            log.error("Agent [{}] 注册异常", config.getAgentId(), e);
            throw new RuntimeException("Agent 注册失败", e);
        }
    }

    /**
     * 从 Registry 注销本 Agent
     * 注销前停止心跳
     */
    public void deregister() {
        if (config.getAgentId() == null) {
            return;
        }
        log.info("正在注销 Agent [{}]...", config.getAgentId());
        stopHeartbeat();
        try {
            DeregisterRequest request = DeregisterRequest.newBuilder()
                    .setAgentId(config.getAgentId())
                    .setSessionToken(sessionToken != null ? sessionToken : "")
                    .build();
            registryStub.deregister(request);
            log.info("Agent [{}] 注销成功", config.getAgentId());
        } catch (Exception e) {
            log.warn("Agent [{}] 注销时发生异常（忽略）", config.getAgentId(), e);
        }
    }

    /**
     * 按 Agent 类型发现 Agent 列表
     *
     * @param agentType Agent 类型
     * @return 匹配的 AgentInfo 列表
     */
    public List<AgentInfo> discover(String agentType) {
        log.debug("发现 Agent，类型: {}", agentType);
        DiscoverRequest request = DiscoverRequest.newBuilder()
                .setAgentType(agentType)
                .setSessionToken(sessionToken != null ? sessionToken : "")
                .build();
        DiscoverResponse response = registryStub.discover(request);
        return response.getAgentsList();
    }

    /**
     * 按能力发现 Agent 列表
     *
     * @param capability 能力名称
     * @return 匹配的 AgentInfo 列表
     */
    public List<AgentInfo> discoverByCapability(String capability) {
        log.debug("发现 Agent，能力: {}", capability);
        DiscoverRequest request = DiscoverRequest.newBuilder()
                .setCapability(capability)
                .setSessionToken(sessionToken != null ? sessionToken : "")
                .build();
        DiscoverResponse response = registryStub.discover(request);
        return response.getAgentsList();
    }

    /**
     * 同步请求/响应（使用默认超时）
     *
     * @param toAgentId 目标 Agent ID
     * @param payload   请求载荷
     * @return 响应消息
     */
    public Message sendRequest(String toAgentId, Map<String, Object> payload) {
        return sendRequest(toAgentId, payload, config.getRequestTimeoutMs());
    }

    /**
     * 同步请求/响应（指定超时）
     * 先通过 Registry 发现目标 Agent 的地址，再直连发送请求
     *
     * @param toAgentId 目标 Agent ID
     * @param payload   请求载荷
     * @param timeoutMs 超时时间（毫秒）
     * @return 响应消息
     */
    public Message sendRequest(String toAgentId, Map<String, Object> payload, int timeoutMs) {
        log.debug("向 Agent [{}] 发送同步请求", toAgentId);

        // 通过 Registry 查找目标 Agent 地址
        AgentInfo targetAgent = findAgentById(toAgentId);
        if (targetAgent == null) {
            throw new RuntimeException("未找到目标 Agent: " + toAgentId);
        }

        // 获取或创建到目标 Agent 的 Channel
        ManagedChannel targetChannel = getOrCreateChannel(targetAgent.getHost(), targetAgent.getPort());
        MessagingServiceGrpc.MessagingServiceBlockingStub targetStub =
                MessagingServiceGrpc.newBlockingStub(targetChannel)
                        .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS);

        // 构建请求消息
        SendMessageRequest request = SendMessageRequest.newBuilder()
                .setMessageId(generateMessageId())
                .setFromAgentId(config.getAgentId())
                .setToAgentId(toAgentId)
                .setPayload(mapToStruct(payload))
                .setSessionToken(sessionToken != null ? sessionToken : "")
                .build();

        // 发送并等待响应
        SendMessageResponse response = targetStub.sendRequest(request);
        return response.getMessage();
    }

    /**
     * 单向发送消息（不等待响应）
     *
     * @param toAgentId 目标 Agent ID
     * @param topic     消息主题
     * @param payload   消息载荷
     * @return 消息 ID
     */
    public String send(String toAgentId, String topic, Map<String, Object> payload) {
        log.debug("向 Agent [{}] 单向发送消息，topic: {}", toAgentId, topic);

        // 通过 Registry 查找目标 Agent 地址
        AgentInfo targetAgent = findAgentById(toAgentId);
        if (targetAgent == null) {
            throw new RuntimeException("未找到目标 Agent: " + toAgentId);
        }

        // 获取或创建到目标 Agent 的 Channel
        ManagedChannel targetChannel = getOrCreateChannel(targetAgent.getHost(), targetAgent.getPort());
        MessagingServiceGrpc.MessagingServiceBlockingStub targetStub =
                MessagingServiceGrpc.newBlockingStub(targetChannel)
                        .withDeadlineAfter(config.getRequestTimeoutMs(), TimeUnit.MILLISECONDS);

        String messageId = generateMessageId();
        // 构建单向消息请求
        SendOneWayRequest request = SendOneWayRequest.newBuilder()
                .setMessageId(messageId)
                .setFromAgentId(config.getAgentId())
                .setToAgentId(toAgentId)
                .setTopic(topic)
                .setPayload(mapToStruct(payload))
                .setSessionToken(sessionToken != null ? sessionToken : "")
                .build();

        targetStub.send(request);
        return messageId;
    }

    /**
     * 发布消息到指定 topic（通过 Registry PubSub）
     *
     * @param topic   主题名称
     * @param payload 消息载荷
     * @return 消息 ID
     */
    public String publish(String topic, Map<String, Object> payload) {
        log.debug("发布消息到 topic: {}", topic);
        String messageId = generateMessageId();
        PublishRequest request = PublishRequest.newBuilder()
                .setMessageId(messageId)
                .setFromAgentId(config.getAgentId())
                .setTopic(topic)
                .setPayload(mapToStruct(payload))
                .setSessionToken(sessionToken != null ? sessionToken : "")
                .build();
        pubSubStub.publish(request);
        return messageId;
    }

    /**
     * 委托任务给目标 Agent（使用默认超时）
     *
     * @param toAgentId 目标 Agent ID
     * @param taskType  任务类型
     * @param input     任务输入
     * @return 任务 ID
     */
    public String delegateTask(String toAgentId, String taskType, Map<String, Object> input) {
        return delegateTask(toAgentId, taskType, input, config.getRequestTimeoutMs() / 1000);
    }

    /**
     * 委托任务给目标 Agent（指定超时秒数）
     *
     * @param toAgentId      目标 Agent ID
     * @param taskType       任务类型
     * @param input          任务输入
     * @param timeoutSeconds 超时时间（秒）
     * @return 任务 ID
     */
    public String delegateTask(String toAgentId, String taskType, Map<String, Object> input, int timeoutSeconds) {
        log.debug("委托任务给 Agent [{}]，任务类型: {}", toAgentId, taskType);

        // 通过 Registry 查找目标 Agent 地址
        AgentInfo targetAgent = findAgentById(toAgentId);
        if (targetAgent == null) {
            throw new RuntimeException("未找到目标 Agent: " + toAgentId);
        }

        // 获取或创建到目标 Agent 的 Channel
        ManagedChannel targetChannel = getOrCreateChannel(targetAgent.getHost(), targetAgent.getPort());
        TaskServiceGrpc.TaskServiceBlockingStub targetTaskStub =
                TaskServiceGrpc.newBlockingStub(targetChannel)
                        .withDeadlineAfter(timeoutSeconds, TimeUnit.SECONDS);

        // 构建任务委托请求
        DelegateTaskRequest request = DelegateTaskRequest.newBuilder()
                .setDelegatorAgentId(config.getAgentId())
                .setExecutorAgentId(toAgentId)
                .setTaskType(taskType)
                .setInput(mapToStruct(input))
                .setTimeoutSeconds(timeoutSeconds)
                .setSessionToken(sessionToken != null ? sessionToken : "")
                .build();

        DelegateTaskResponse response = targetTaskStub.delegateTask(request);
        log.info("任务委托成功，taskId: {}", response.getTaskId());
        return response.getTaskId();
    }

    /**
     * 查询任务状态
     *
     * @param taskId 任务 ID
     * @return 任务信息
     */
    public Task getTaskStatus(String taskId) {
        log.debug("查询任务状态，taskId: {}", taskId);
        GetTaskStatusRequest request = GetTaskStatusRequest.newBuilder()
                .setTaskId(taskId)
                .setSessionToken(sessionToken != null ? sessionToken : "")
                .build();
        GetTaskStatusResponse response = taskStub.getTaskStatus(request);
        return response.getTask();
    }

    /**
     * 取消任务
     *
     * @param taskId 任务 ID
     * @param reason 取消原因
     * @return 是否取消成功
     */
    public boolean cancelTask(String taskId, String reason) {
        log.info("取消任务，taskId: {}，原因: {}", taskId, reason);
        CancelTaskRequest request = CancelTaskRequest.newBuilder()
                .setTaskId(taskId)
                .setReason(reason)
                .setSessionToken(sessionToken != null ? sessionToken : "")
                .build();
        CancelTaskResponse response = taskStub.cancelTask(request);
        return response.getSuccess();
    }

    /**
     * 启动心跳定时任务
     * 每隔 heartbeatIntervalMs 毫秒向 Registry 发送一次心跳
     */
    private void startHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "a2a-heartbeat-" + config.getAgentId());
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                HeartbeatRequest request = HeartbeatRequest.newBuilder()
                        .setAgentId(config.getAgentId())
                        .setSessionToken(sessionToken != null ? sessionToken : "")
                        .build();
                registryStub.heartbeat(request);
                log.debug("心跳发送成功，agentId: {}", config.getAgentId());
            } catch (Exception e) {
                log.warn("心跳发送失败，agentId: {}", config.getAgentId(), e);
            }
        }, config.getHeartbeatIntervalMs(), config.getHeartbeatIntervalMs(), TimeUnit.MILLISECONDS);
        log.info("心跳已启动，间隔: {}ms", config.getHeartbeatIntervalMs());
    }

    /**
     * 停止心跳定时任务
     */
    private void stopHeartbeat() {
        if (heartbeatExecutor != null && !heartbeatExecutor.isShutdown()) {
            heartbeatExecutor.shutdownNow();
            log.info("心跳已停止，agentId: {}", config.getAgentId());
        }
    }

    /**
     * 生成唯一消息 ID
     *
     * @return UUID 字符串
     */
    private String generateMessageId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 将 Java Map 转换为 protobuf Struct
     * 支持 String、Number、Boolean、Map、List 类型的递归转换
     *
     * @param map 原始 Map
     * @return protobuf Struct
     */
    @SuppressWarnings("unchecked")
    private Struct mapToStruct(Map<String, Object> map) {
        if (map == null) {
            return Struct.getDefaultInstance();
        }
        Struct.Builder structBuilder = Struct.newBuilder();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            structBuilder.putFields(entry.getKey(), objectToValue(entry.getValue()));
        }
        return structBuilder.build();
    }

    /**
     * 将 Java 对象转换为 protobuf Value
     *
     * @param obj 原始对象
     * @return protobuf Value
     */
    @SuppressWarnings("unchecked")
    private Value objectToValue(Object obj) {
        if (obj == null) {
            return Value.newBuilder().setNullValue(com.google.protobuf.NullValue.NULL_VALUE).build();
        } else if (obj instanceof String) {
            return Value.newBuilder().setStringValue((String) obj).build();
        } else if (obj instanceof Number) {
            return Value.newBuilder().setNumberValue(((Number) obj).doubleValue()).build();
        } else if (obj instanceof Boolean) {
            return Value.newBuilder().setBoolValue((Boolean) obj).build();
        } else if (obj instanceof Map) {
            return Value.newBuilder().setStructValue(mapToStruct((Map<String, Object>) obj)).build();
        } else if (obj instanceof List) {
            com.google.protobuf.ListValue.Builder listBuilder = com.google.protobuf.ListValue.newBuilder();
            for (Object item : (List<?>) obj) {
                listBuilder.addValues(objectToValue(item));
            }
            return Value.newBuilder().setListValue(listBuilder.build()).build();
        } else {
            // 其他类型转为字符串
            return Value.newBuilder().setStringValue(obj.toString()).build();
        }
    }

    /**
     * 通过 Agent ID 在 Registry 中查找 Agent 信息
     *
     * @param agentId Agent ID
     * @return AgentInfo，未找到返回 null
     */
    private AgentInfo findAgentById(String agentId) {
        try {
            GetAgentRequest request = GetAgentRequest.newBuilder()
                    .setAgentId(agentId)
                    .setSessionToken(sessionToken != null ? sessionToken : "")
                    .build();
            GetAgentResponse response = registryStub.getAgent(request);
            return response.hasAgent() ? response.getAgent() : null;
        } catch (Exception e) {
            log.error("查找 Agent [{}] 失败", agentId, e);
            return null;
        }
    }

    /**
     * 从连接池获取或新建到指定地址的 gRPC Channel
     *
     * @param host 目标主机
     * @param port 目标端口
     * @return ManagedChannel
     */
    private ManagedChannel getOrCreateChannel(String host, int port) {
        String key = host + ":" + port;
        return channelPool.computeIfAbsent(key, k -> {
            log.debug("创建新的 gRPC Channel: {}", k);
            return ManagedChannelBuilder.forAddress(host, port)
                    .usePlaintext()
                    .build();
        });
    }

    /**
     * 获取 Registry 异步 Stub（供 PubSubSubscriber 等使用）
     *
     * @return RegistryServiceStub
     */
    public RegistryServiceGrpc.RegistryServiceStub getRegistryAsyncStub() {
        return registryAsyncStub;
    }

    /**
     * 获取 PubSub 异步 Stub
     *
     * @return PubSubServiceStub
     */
    public PubSubServiceGrpc.PubSubServiceStub getPubSubAsyncStub() {
        return PubSubServiceGrpc.newStub(registryChannel);
    }

    /**
     * 获取 Task 异步 Stub
     *
     * @return TaskServiceStub
     */
    public TaskServiceGrpc.TaskServiceStub getTaskAsyncStub() {
        return TaskServiceGrpc.newStub(registryChannel);
    }

    /**
     * 获取当前会话 token
     *
     * @return sessionToken
     */
    public String getSessionToken() {
        return sessionToken;
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
     * 关闭客户端，注销 Agent，停止心跳，关闭所有 Channel
     */
    @Override
    public void close() {
        deregister();
        stopHeartbeat();
        // 关闭连接池中所有 Channel
        channelPool.values().forEach(channel -> {
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.shutdownNow();
            }
        });
        channelPool.clear();
        // 关闭 Registry Channel
        try {
            registryChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            registryChannel.shutdownNow();
        }
        log.info("A2AClient 已关闭，agentId: {}", config.getAgentId());
    }
}
