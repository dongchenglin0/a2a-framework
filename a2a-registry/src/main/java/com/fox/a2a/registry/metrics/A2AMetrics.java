package com.fox.a2a.registry.metrics;

import io.micrometer.core.instrument.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A2A Framework 核心指标
 * 所有 Prometheus 指标在此统一定义和管理
 */
@Slf4j
@Component
public class A2AMetrics {

    // ===== 计数器 =====
    /** Agent 注册总次数 */
    private final Counter agentRegisterTotal;
    /** Agent 注销总次数 */
    private final Counter agentDeregisterTotal;
    /** 消息发送总次数 */
    private final Counter messageSendTotal;
    /** 消息发送失败次数 */
    private final Counter messageSendErrors;
    /** 心跳接收总次数 */
    private final Counter heartbeatTotal;
    /** 心跳超时次数 */
    private final Counter heartbeatTimeoutTotal;
    /** 任务委托总次数 */
    private final Counter taskDelegateTotal;
    /** 任务完成次数 */
    private final Counter taskCompleteTotal;
    /** 任务失败次数 */
    private final Counter taskFailTotal;
    /** JWT 认证成功次数 */
    private final Counter authSuccessTotal;
    /** JWT 认证失败次数 */
    private final Counter authFailTotal;

    // ===== 仪表盘（实时值） =====
    /** 当前在线 Agent 数量 */
    private final AtomicInteger onlineAgentCount = new AtomicInteger(0);
    /** 当前离线 Agent 数量 */
    private final AtomicInteger offlineAgentCount = new AtomicInteger(0);
    /** 当前活跃 PubSub 订阅数 */
    private final AtomicInteger activePubSubSubscriptions = new AtomicInteger(0);
    /** 当前进行中的任务数 */
    private final AtomicInteger runningTaskCount = new AtomicInteger(0);

    // ===== 直方图（延迟分布） =====
    /** 消息请求/响应延迟（毫秒） */
    private final Timer messageRequestLatency;
    /** Agent 发现延迟（毫秒） */
    private final Timer agentDiscoverLatency;
    /** 任务执行时长（毫秒） */
    private final Timer taskExecutionDuration;

    public A2AMetrics(MeterRegistry registry) {
        // 注册计数器
        agentRegisterTotal = Counter.builder("a2a_agent_register_total")
                .description("Agent 注册总次数")
                .register(registry);
        agentDeregisterTotal = Counter.builder("a2a_agent_deregister_total")
                .description("Agent 注销总次数")
                .register(registry);
        messageSendTotal = Counter.builder("a2a_message_send_total")
                .description("消息发送总次数")
                .register(registry);
        messageSendErrors = Counter.builder("a2a_message_send_errors_total")
                .description("消息发送失败次数")
                .register(registry);
        heartbeatTotal = Counter.builder("a2a_heartbeat_total")
                .description("心跳接收总次数")
                .register(registry);
        heartbeatTimeoutTotal = Counter.builder("a2a_heartbeat_timeout_total")
                .description("心跳超时次数")
                .register(registry);
        taskDelegateTotal = Counter.builder("a2a_task_delegate_total")
                .description("任务委托总次数")
                .register(registry);
        taskCompleteTotal = Counter.builder("a2a_task_complete_total")
                .description("任务完成次数")
                .register(registry);
        taskFailTotal = Counter.builder("a2a_task_fail_total")
                .description("任务失败次数")
                .register(registry);
        authSuccessTotal = Counter.builder("a2a_auth_success_total")
                .description("JWT 认证成功次数")
                .register(registry);
        authFailTotal = Counter.builder("a2a_auth_fail_total")
                .description("JWT 认证失败次数")
                .register(registry);

        // 注册仪表盘（Gauge）
        Gauge.builder("a2a_agent_online_count", onlineAgentCount, AtomicInteger::get)
                .description("当前在线 Agent 数量")
                .register(registry);
        Gauge.builder("a2a_agent_offline_count", offlineAgentCount, AtomicInteger::get)
                .description("当前离线 Agent 数量")
                .register(registry);
        Gauge.builder("a2a_pubsub_subscriptions_active", activePubSubSubscriptions, AtomicInteger::get)
                .description("当前活跃 PubSub 订阅数")
                .register(registry);
        Gauge.builder("a2a_task_running_count", runningTaskCount, AtomicInteger::get)
                .description("当前进行中的任务数")
                .register(registry);

        // 注册直方图（Timer）
        messageRequestLatency = Timer.builder("a2a_message_request_latency_ms")
                .description("消息请求/响应延迟")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        agentDiscoverLatency = Timer.builder("a2a_agent_discover_latency_ms")
                .description("Agent 发现延迟")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        taskExecutionDuration = Timer.builder("a2a_task_execution_duration_ms")
                .description("任务执行时长")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    // ===== 计数器公共方法 =====

    /** 记录 Agent 注册 */
    public void recordAgentRegister() {
        agentRegisterTotal.increment();
    }

    /** 记录 Agent 注销 */
    public void recordAgentDeregister() {
        agentDeregisterTotal.increment();
    }

    /** 记录消息发送 */
    public void recordMessageSend() {
        messageSendTotal.increment();
    }

    /** 记录消息发送失败 */
    public void recordMessageError() {
        messageSendErrors.increment();
    }

    /** 记录心跳 */
    public void recordHeartbeat() {
        heartbeatTotal.increment();
    }

    /** 记录心跳超时 */
    public void recordHeartbeatTimeout() {
        heartbeatTimeoutTotal.increment();
    }

    /** 记录任务委托 */
    public void recordTaskDelegate() {
        taskDelegateTotal.increment();
    }

    /** 记录任务完成 */
    public void recordTaskComplete() {
        taskCompleteTotal.increment();
    }

    /** 记录任务失败 */
    public void recordTaskFail() {
        taskFailTotal.increment();
    }

    /** 记录 JWT 认证成功 */
    public void recordAuthSuccess() {
        authSuccessTotal.increment();
    }

    /** 记录 JWT 认证失败 */
    public void recordAuthFail() {
        authFailTotal.increment();
    }

    // ===== Gauge 公共方法 =====

    /** 设置在线 Agent 数量 */
    public void setOnlineAgentCount(int count) {
        onlineAgentCount.set(count);
    }

    /** 设置离线 Agent 数量 */
    public void setOfflineAgentCount(int count) {
        offlineAgentCount.set(count);
    }

    /** 增加 PubSub 订阅数 */
    public void incrementPubSubSubscriptions() {
        activePubSubSubscriptions.incrementAndGet();
    }

    /** 减少 PubSub 订阅数 */
    public void decrementPubSubSubscriptions() {
        activePubSubSubscriptions.decrementAndGet();
    }

    /** 增加运行中任务数 */
    public void incrementRunningTasks() {
        runningTaskCount.incrementAndGet();
    }

    /** 减少运行中任务数 */
    public void decrementRunningTasks() {
        runningTaskCount.decrementAndGet();
    }

    // ===== Timer 公共方法 =====

    /**
     * 开始计时（在操作开始前调用）
     * 示例：Timer.Sample sample = metrics.startTimer();
     */
    public Timer.Sample startTimer() {
        return Timer.start();
    }

    /** 记录消息延迟（在操作结束后调用） */
    public void recordMessageLatency(Timer.Sample sample) {
        sample.stop(messageRequestLatency);
    }

    /** 记录 Agent 发现延迟 */
    public void recordDiscoverLatency(Timer.Sample sample) {
        sample.stop(agentDiscoverLatency);
    }

    /** 记录任务执行时长 */
    public void recordTaskDuration(Timer.Sample sample) {
        sample.stop(taskExecutionDuration);
    }
}
