package com.fox.a2a.core;

import com.fox.a2a.core.handler.MessageHandler;
import com.fox.a2a.core.handler.TaskHandler;
import com.fox.a2a.proto.*;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Agent gRPC 服务器
 * 监听指定端口，接收其他 Agent 发来的消息和任务委托请求
 */
@Slf4j
public class AgentServer {

    /** 监听端口 */
    private final int port;

    /** gRPC 服务器实例 */
    private Server server;

    /** 消息处理器（处理同步请求和单向消息） */
    private volatile MessageHandler messageHandler;

    /** 任务处理器（处理任务委托） */
    private volatile TaskHandler taskHandler;

    /** 订阅的 topic 列表 */
    private final List<String> subscribedTopics;

    /** 异步任务执行线程池（用于异步执行委托任务） */
    private final ExecutorService taskExecutor;

    /**
     * 构造函数
     *
     * @param port             监听端口
     * @param messageHandler   消息处理器
     * @param taskHandler      任务处理器
     * @param subscribedTopics 订阅的 topic 列表
     */
    public AgentServer(int port, MessageHandler messageHandler, TaskHandler taskHandler,
                       List<String> subscribedTopics) {
        this.port = port;
        this.messageHandler = messageHandler;
        this.taskHandler = taskHandler;
        this.subscribedTopics = subscribedTopics != null ? subscribedTopics : new ArrayList<>();
        this.taskExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "a2a-task-executor");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 简化构造函数（无 handler，后续通过 setter 设置）
     *
     * @param port 监听端口
     */
    public AgentServer(int port) {
        this(port, null, null, null);
    }

    /**
     * 启动 gRPC 服务器
     *
     * @throws IOException 启动失败时抛出
     */
    public void start() throws IOException {
        server = ServerBuilder.forPort(port)
                .addService(new MessagingServiceImpl())
                .addService(new TaskServiceImpl())
                .build()
                .start();
        log.info("AgentServer 已启动，监听端口: {}", port);

        // 注册 JVM 关闭钩子，确保优雅停机
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("JVM 关闭，正在停止 AgentServer...");
            AgentServer.this.stop();
        }));
    }

    /**
     * 停止 gRPC 服务器
     */
    public void stop() {
        if (server != null && !server.isShutdown()) {
            try {
                server.shutdown().awaitTermination(10, TimeUnit.SECONDS);
                log.info("AgentServer 已停止");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                server.shutdownNow();
            }
        }
        taskExecutor.shutdownNow();
    }

    /**
     * 阻塞等待服务器终止
     *
     * @throws InterruptedException 线程中断时抛出
     */
    public void awaitTermination() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    /**
     * 设置消息处理器
     *
     * @param handler 消息处理器
     */
    public void setMessageHandler(MessageHandler handler) {
        this.messageHandler = handler;
    }

    /**
     * 设置任务处理器
     *
     * @param handler 任务处理器
     */
    public void setTaskHandler(TaskHandler handler) {
        this.taskHandler = handler;
    }

    /**
     * 将 protobuf Struct 转换为 Java Map
     *
     * @param struct protobuf Struct
     * @return Java Map
     */
    private Map<String, Object> structToMap(Struct struct) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Value> entry : struct.getFieldsMap().entrySet()) {
            result.put(entry.getKey(), valueToObject(entry.getValue()));
        }
        return result;
    }

    /**
     * 将 protobuf Value 转换为 Java 对象
     *
     * @param value protobuf Value
     * @return Java 对象
     */
    private Object valueToObject(Value value) {
        switch (value.getKindCase()) {
            case STRING_VALUE:
                return value.getStringValue();
            case NUMBER_VALUE:
                return value.getNumberValue();
            case BOOL_VALUE:
                return value.getBoolValue();
            case STRUCT_VALUE:
                return structToMap(value.getStructValue());
            case LIST_VALUE:
                List<Object> list = new ArrayList<>();
                for (Value item : value.getListValue().getValuesList()) {
                    list.add(valueToObject(item));
                }
                return list;
            case NULL_VALUE:
            default:
                return null;
        }
    }

    /**
     * 将 Java Map 转换为 protobuf Struct
     *
     * @param map Java Map
     * @return protobuf Struct
     */
    @SuppressWarnings("unchecked")
    private Struct mapToStruct(Map<String, Object> map) {
        if (map == null) {
            return Struct.getDefaultInstance();
        }
        Struct.Builder builder = Struct.newBuilder();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            builder.putFields(entry.getKey(), objectToValue(entry.getValue()));
        }
        return builder.build();
    }

    /**
     * 将 Java 对象转换为 protobuf Value
     *
     * @param obj Java 对象
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
            return Value.newBuilder().setStringValue(obj.toString()).build();
        }
    }

    // ==================== 内部 gRPC 服务实现 ====================

    /**
     * 消息服务实现
     * 处理同步请求（SendRequest）和单向消息（Send）
     */
    private class MessagingServiceImpl extends MessagingServiceGrpc.MessagingServiceImplBase {

        /**
         * 处理同步请求/响应
         * 调用 messageHandler.handleRequest() 获取响应，并返回给调用方
         */
        @Override
        public void sendRequest(SendMessageRequest request,
                                StreamObserver<SendMessageResponse> responseObserver) {
            log.debug("收到同步请求，来自 Agent [{}]，messageId: {}",
                    request.getFromAgentId(), request.getMessageId());
            try {
                Map<String, Object> responsePayload;
                if (messageHandler != null) {
                    // 将 protobuf Struct 转为 Map，调用业务处理器
                    Map<String, Object> payload = structToMap(request.getPayload());
                    responsePayload = messageHandler.handleRequest(
                            request.getFromAgentId(),
                            request.getMessageId(),
                            payload
                    );
                } else {
                    log.warn("未设置 MessageHandler，返回空响应");
                    responsePayload = Collections.emptyMap();
                }

                // 构建响应消息
                Message responseMessage = Message.newBuilder()
                        .setMessageId(UUID.randomUUID().toString())
                        .setFromAgentId(request.getToAgentId())
                        .setToAgentId(request.getFromAgentId())
                        .setPayload(mapToStruct(responsePayload))
                        .build();

                SendMessageResponse response = SendMessageResponse.newBuilder()
                        .setMessage(responseMessage)
                        .setSuccess(true)
                        .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } catch (Exception e) {
                log.error("处理同步请求异常，messageId: {}", request.getMessageId(), e);
                responseObserver.onError(io.grpc.Status.INTERNAL
                        .withDescription("处理请求失败: " + e.getMessage())
                        .asRuntimeException());
            }
        }

        /**
         * 处理单向消息（Fire-and-Forget）
         * 调用 messageHandler.handleMessage()，不返回业务数据
         */
        @Override
        public void send(SendOneWayRequest request,
                         StreamObserver<SendOneWayResponse> responseObserver) {
            log.debug("收到单向消息，来自 Agent [{}]，topic: {}",
                    request.getFromAgentId(), request.getTopic());
            try {
                if (messageHandler != null) {
                    Map<String, Object> payload = structToMap(request.getPayload());
                    messageHandler.handleMessage(
                            request.getFromAgentId(),
                            request.getTopic(),
                            payload
                    );
                } else {
                    log.warn("未设置 MessageHandler，忽略单向消息");
                }

                SendOneWayResponse response = SendOneWayResponse.newBuilder()
                        .setSuccess(true)
                        .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } catch (Exception e) {
                log.error("处理单向消息异常，messageId: {}", request.getMessageId(), e);
                responseObserver.onError(io.grpc.Status.INTERNAL
                        .withDescription("处理消息失败: " + e.getMessage())
                        .asRuntimeException());
            }
        }
    }

    /**
     * 任务服务实现
     * 处理任务委托（DelegateTask）、状态查询（GetTaskStatus）、取消（CancelTask）
     */
    private class TaskServiceImpl extends TaskServiceGrpc.TaskServiceImplBase {

        /**
         * 处理任务委托
         * 立即返回 accepted=true，异步执行任务
         */
        @Override
        public void delegateTask(DelegateTaskRequest request,
                                 StreamObserver<DelegateTaskResponse> responseObserver) {
            String taskId = UUID.randomUUID().toString();
            log.info("收到任务委托，来自 Agent [{}]，taskType: {}，taskId: {}",
                    request.getDelegatorAgentId(), request.getTaskType(), taskId);

            // 立即返回 accepted，异步执行任务
            DelegateTaskResponse response = DelegateTaskResponse.newBuilder()
                    .setTaskId(taskId)
                    .setAccepted(true)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

            // 异步执行任务
            if (taskHandler != null) {
                taskExecutor.submit(() -> {
                    try {
                        Map<String, Object> input = structToMap(request.getInput());
                        Map<String, Object> result = taskHandler.handleTask(
                                taskId,
                                request.getTaskType(),
                                request.getDelegatorAgentId(),
                                input
                        );
                        log.info("任务 [{}] 执行完成", taskId);
                    } catch (Exception e) {
                        log.error("任务 [{}] 执行失败", taskId, e);
                    }
                });
            } else {
                log.warn("未设置 TaskHandler，任务 [{}] 将被忽略", taskId);
            }
        }

        /**
         * 查询任务状态
         */
        @Override
        public void getTaskStatus(GetTaskStatusRequest request,
                                  StreamObserver<GetTaskStatusResponse> responseObserver) {
            log.debug("查询任务状态，taskId: {}", request.getTaskId());
            // 此处返回基础响应，实际状态管理由上层业务实现
            Task task = Task.newBuilder()
                    .setTaskId(request.getTaskId())
                    .setStatus(TaskStatus.TASK_STATUS_RUNNING)
                    .build();
            GetTaskStatusResponse response = GetTaskStatusResponse.newBuilder()
                    .setTask(task)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        /**
         * 取消任务
         */
        @Override
        public void cancelTask(CancelTaskRequest request,
                               StreamObserver<CancelTaskResponse> responseObserver) {
            log.info("收到取消任务请求，taskId: {}，原因: {}", request.getTaskId(), request.getReason());
            CancelTaskResponse response = CancelTaskResponse.newBuilder()
                    .setSuccess(true)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        /**
         * 监听任务进度（流式）
         */
        @Override
        public void watchTask(WatchTaskRequest request,
                              StreamObserver<TaskEvent> responseObserver) {
            log.debug("开始监听任务进度，taskId: {}", request.getTaskId());
            // 发送一个初始事件，实际实现中应持续推送进度
            TaskEvent event = TaskEvent.newBuilder()
                    .setTaskId(request.getTaskId())
                    .setEventType(TaskEventType.TASK_EVENT_STARTED)
                    .build();
            responseObserver.onNext(event);
            responseObserver.onCompleted();
        }
    }
}
