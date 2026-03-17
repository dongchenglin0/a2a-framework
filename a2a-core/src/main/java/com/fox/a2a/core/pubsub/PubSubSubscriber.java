package com.fox.a2a.core.pubsub;

import com.fox.a2a.proto.*;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * PubSub 订阅者
 * 订阅 Registry 的 PubSub 消息，支持断线自动重连（指数退避）
 */
@Slf4j
public class PubSubSubscriber {

    /** PubSub 异步 Stub */
    private final PubSubServiceGrpc.PubSubServiceStub asyncStub;

    /** 本 Agent ID */
    private final String agentId;

    /** 会话 token */
    private final String sessionToken;

    /** 是否已订阅（运行中） */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 当前订阅的 topic 列表 */
    private volatile List<String> currentTopics;

    /** 当前消息处理回调 */
    private volatile Consumer<PubSubMessage> currentHandler;

    /** 重连调度器 */
    private final ScheduledExecutorService reconnectScheduler;

    /** 当前重连延迟（毫秒），指数退避 */
    private final AtomicInteger reconnectDelayMs = new AtomicInteger(1000);

    /** 最大重连延迟（毫秒） */
    private static final int MAX_RECONNECT_DELAY_MS = 30_000;

    /** 最小重连延迟（毫秒） */
    private static final int MIN_RECONNECT_DELAY_MS = 1_000;

    /**
     * 构造函数
     *
     * @param asyncStub    PubSub 异步 Stub
     * @param agentId      本 Agent ID
     * @param sessionToken 会话 token
     */
    public PubSubSubscriber(PubSubServiceGrpc.PubSubServiceStub asyncStub,
                            String agentId,
                            String sessionToken) {
        this.asyncStub = asyncStub;
        this.agentId = agentId;
        this.sessionToken = sessionToken;
        this.reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "a2a-pubsub-reconnect-" + agentId);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 订阅指定 topic 列表
     * 建立流式连接，收到消息时调用 handler；断线后自动重连
     *
     * @param topics  要订阅的 topic 列表
     * @param handler 消息处理回调
     */
    public void subscribe(List<String> topics, Consumer<PubSubMessage> handler) {
        if (!running.compareAndSet(false, true)) {
            log.warn("PubSubSubscriber [{}] 已在运行中，忽略重复订阅", agentId);
            return;
        }
        this.currentTopics = topics;
        this.currentHandler = handler;
        log.info("Agent [{}] 开始订阅 topics: {}", agentId, topics);
        doSubscribe(topics, handler);
    }

    /**
     * 执行实际的 gRPC 流式订阅
     *
     * @param topics  topic 列表
     * @param handler 消息处理回调
     */
    private void doSubscribe(List<String> topics, Consumer<PubSubMessage> handler) {
        // 构建订阅请求
        PubSubSubscribeRequest request = PubSubSubscribeRequest.newBuilder()
                .setAgentId(agentId)
                .addAllTopics(topics)
                .setSessionToken(sessionToken != null ? sessionToken : "")
                .build();

        // 建立流式连接
        asyncStub.subscribe(request, new StreamObserver<PubSubMessage>() {

            @Override
            public void onNext(PubSubMessage message) {
                // 收到消息，重置重连延迟
                reconnectDelayMs.set(MIN_RECONNECT_DELAY_MS);
                log.debug("Agent [{}] 收到 PubSub 消息，topic: {}, messageId: {}",
                        agentId, message.getTopic(), message.getMessageId());
                try {
                    handler.accept(message);
                } catch (Exception e) {
                    log.error("处理 PubSub 消息异常，messageId: {}", message.getMessageId(), e);
                }
            }

            @Override
            public void onError(Throwable t) {
                if (!running.get()) {
                    // 已主动取消订阅，不重连
                    return;
                }
                log.warn("Agent [{}] PubSub 连接断开: {}，将在 {}ms 后重连",
                        agentId, t.getMessage(), reconnectDelayMs.get());
                scheduleReconnect();
            }

            @Override
            public void onCompleted() {
                if (!running.get()) {
                    log.info("Agent [{}] PubSub 订阅已正常结束", agentId);
                    return;
                }
                log.warn("Agent [{}] PubSub 流意外结束，将在 {}ms 后重连",
                        agentId, reconnectDelayMs.get());
                scheduleReconnect();
            }
        });
    }

    /**
     * 调度重连任务（指数退避）
     */
    private void scheduleReconnect() {
        int delay = reconnectDelayMs.get();
        // 指数退避：延迟翻倍，不超过最大值
        int nextDelay = Math.min(delay * 2, MAX_RECONNECT_DELAY_MS);
        reconnectDelayMs.set(nextDelay);

        reconnectScheduler.schedule(() -> {
            if (!running.get()) {
                return;
            }
            log.info("Agent [{}] 正在重连 PubSub...", agentId);
            try {
                doSubscribe(currentTopics, currentHandler);
            } catch (Exception e) {
                log.error("Agent [{}] PubSub 重连失败", agentId, e);
                scheduleReconnect();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * 取消订阅，停止重连调度器
     */
    public void unsubscribe() {
        if (running.compareAndSet(true, false)) {
            log.info("Agent [{}] 取消 PubSub 订阅", agentId);
            reconnectScheduler.shutdownNow();
        }
    }

    /**
     * 判断是否正在运行
     *
     * @return true 表示订阅中
     */
    public boolean isRunning() {
        return running.get();
    }
}
