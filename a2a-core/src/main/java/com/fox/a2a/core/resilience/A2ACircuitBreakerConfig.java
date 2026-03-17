package com.fox.a2a.core.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * A2A 熔断器配置
 * 为每个目标 Agent 创建独立的熔断器实例
 * 防止单个 Agent 故障导致调用方雪崩
 *
 * 熔断器状态机：
 *   CLOSED（正常）→ OPEN（熔断）→ HALF_OPEN（探测）→ CLOSED（恢复）
 */
@Slf4j
public class A2ACircuitBreakerConfig {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;

    public A2ACircuitBreakerConfig() {
        // 默认熔断器配置
        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig cbConfig =
            io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .slidingWindowSize(10)                              // 滑动窗口大小：最近 10 次调用
                .failureRateThreshold(50)                           // 失败率阈值：50% 触发熔断
                .waitDurationInOpenState(Duration.ofSeconds(10))    // 熔断后等待 10s 再进入半开
                .permittedNumberOfCallsInHalfOpenState(3)           // 半开状态允许 3 次探测调用
                .automaticTransitionFromOpenToHalfOpenEnabled(true) // 自动从 OPEN 转为 HALF_OPEN
                .recordExceptions(Exception.class)                  // 所有异常都计入失败
                .build();

        // 默认重试配置
        io.github.resilience4j.retry.RetryConfig retryConfig =
            io.github.resilience4j.retry.RetryConfig.custom()
                .maxAttempts(3)                                          // 最多重试 3 次
                .waitDuration(Duration.ofMillis(500))                    // 重试间隔 500ms
                .retryExceptions(io.grpc.StatusRuntimeException.class)   // 仅对 gRPC 异常重试
                .build();

        this.circuitBreakerRegistry = CircuitBreakerRegistry.of(cbConfig);
        this.retryRegistry = RetryRegistry.of(retryConfig);
    }

    /**
     * 获取或创建指定 Agent 的熔断器
     * 每个 agentId 对应一个独立的熔断器实例，互不影响
     *
     * @param agentId 目标 Agent ID
     * @return 对应的熔断器实例
     */
    public CircuitBreaker getCircuitBreaker(String agentId) {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("agent-" + agentId);
        // 注册状态变更监听，便于运维感知熔断事件
        cb.getEventPublisher()
            .onStateTransition(event ->
                log.warn("Agent [{}] 熔断器状态变更: {} -> {}",
                    agentId,
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState()));
        return cb;
    }

    /**
     * 获取或创建指定 Agent 的重试器
     *
     * @param agentId 目标 Agent ID
     * @return 对应的重试器实例
     */
    public Retry getRetry(String agentId) {
        return retryRegistry.retry("agent-" + agentId);
    }

    /**
     * 重置指定 Agent 的熔断器
     * 适用于 Agent 恢复后手动强制关闭熔断器
     *
     * @param agentId 目标 Agent ID
     */
    public void resetCircuitBreaker(String agentId) {
        circuitBreakerRegistry.circuitBreaker("agent-" + agentId).reset();
        log.info("Agent [{}] 熔断器已重置为 CLOSED 状态", agentId);
    }

    /**
     * 获取所有熔断器的当前状态（用于监控大盘）
     *
     * @return agentId -> 熔断器状态名称 的映射
     */
    public Map<String, String> getAllCircuitBreakerStates() {
        Map<String, String> states = new HashMap<>();
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(cb ->
            states.put(cb.getName(), cb.getState().name()));
        return states;
    }
}
