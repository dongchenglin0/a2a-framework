package com.fox.a2a.core.resilience;

import com.fox.a2a.core.A2AClient;
import com.fox.a2a.core.A2AConfig;
import com.fox.a2a.proto.Message;
import com.fox.a2a.proto.MessageType;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * 带熔断/重试能力的 A2A 客户端包装类
 * 在现有 A2AClient 基础上，对每个目标 Agent 的调用自动应用熔断器和重试策略。
 *
 * 核心特性：
 * 1. 每个目标 Agent 独立熔断器，互不影响
 * 2. gRPC 调用失败自动重试（最多 3 次，间隔 500ms）
 * 3. 熔断器打开时立即执行降级逻辑，不等待超时
 * 4. 支持自定义 FallbackHandler 实现业务级降级
 *
 * 使用示例：
 * <pre>
 * ResilientA2AClient client = new ResilientA2AClient(config);
 * client.start();
 * // 调用时自动应用熔断/重试，熔断时返回降级响应
 * Message response = client.sendRequest("agent-a", payload);
 * // 查看熔断器状态
 * Map&lt;String, String&gt; states = client.getCircuitBreakerStates();
 * </pre>
 */
@Slf4j
public class ResilientA2AClient extends A2AClient {

    /** 熔断器 + 重试配置管理器 */
    private final A2ACircuitBreakerConfig cbConfig;

    /** 可选的自定义降级处理器 */
    private final FallbackHandler fallbackHandler;

    /**
     * 使用默认降级策略构造（返回 ERROR 类型消息）
     *
     * @param config A2A 客户端配置
     */
    public ResilientA2AClient(A2AConfig config) {
        this(config, null);
    }

    /**
     * 使用自定义降级处理器构造
     *
     * @param config          A2A 客户端配置
     * @param fallbackHandler 自定义降级处理器，为 null 时使用默认降级
     */
    public ResilientA2AClient(A2AConfig config, FallbackHandler fallbackHandler) {
        super(config);
        this.cbConfig = new A2ACircuitBreakerConfig();
        this.fallbackHandler = fallbackHandler;
    }

    /**
     * 带熔断/重试的同步请求（使用默认超时）
     * 熔断器打开时立即返回降级响应，不等待超时
     *
     * @param toAgentId 目标 Agent ID
     * @param payload   请求 payload
     * @return 响应消息（熔断时返回 ERROR 类型降级消息）
     */
    @Override
    public Message sendRequest(String toAgentId, Map<String, Object> payload) {
        return executeWithResilience(
            toAgentId,
            () -> super.sendRequest(toAgentId, payload),
            (cause) -> buildFallbackResponse(toAgentId, payload, cause)
        );
    }

    /**
     * 带熔断/重试的同步请求（自定义超时）
     *
     * @param toAgentId 目标 Agent ID
     * @param payload   请求 payload
     * @param timeoutMs 超时时间（毫秒）
     * @return 响应消息（熔断时返回 ERROR 类型降级消息）
     */
    @Override
    public Message sendRequest(String toAgentId, Map<String, Object> payload, int timeoutMs) {
        return executeWithResilience(
            toAgentId,
            () -> super.sendRequest(toAgentId, payload, timeoutMs),
            (cause) -> buildFallbackResponse(toAgentId, payload, cause)
        );
    }

    /**
     * 带熔断/重试的任务委托
     * 熔断器打开时抛出 RuntimeException（任务委托不适合返回降级 ID）
     *
     * @param toAgentId 目标 Agent ID
     * @param taskType  任务类型
     * @param input     任务输入参数
     * @return 任务 ID
     */
    @Override
    public String delegateTask(String toAgentId, String taskType, Map<String, Object> input) {
        return executeWithResilience(
            toAgentId,
            () -> super.delegateTask(toAgentId, taskType, input),
            (cause) -> {
                if (fallbackHandler != null) {
                    return fallbackHandler.handleTaskFallback(toAgentId, taskType, input, cause);
                }
                throw new RuntimeException(
                    "Agent [" + toAgentId + "] 熔断器已打开，任务委托被拒绝", cause);
            }
        );
    }

    /**
     * 核心执行方法：用熔断器 + 重试包装任意调用
     * 执行顺序：重试 → 熔断器 → 降级
     *
     * @param agentId  目标 Agent ID（用于获取对应的熔断器/重试器）
     * @param action   实际调用逻辑
     * @param fallback 降级逻辑（接收失败原因，返回降级结果）
     * @param <T>      返回值类型
     * @return 调用结果或降级结果
     */
    private <T> T executeWithResilience(String agentId,
                                         Callable<T> action,
                                         java.util.function.Function<Throwable, T> fallback) {
        CircuitBreaker cb = cbConfig.getCircuitBreaker(agentId);
        Retry retry = cbConfig.getRetry(agentId);

        // 手动组合：先用 CircuitBreaker 包装，再用 Retry 包装
        Supplier<T> cbDecorated = CircuitBreaker.decorateSupplier(cb, () -> {
            try {
                return action.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Supplier<T> retryDecorated = Retry.decorateSupplier(retry, cbDecorated);

        try {
            return retryDecorated.get();
        } catch (CallNotPermittedException e) {
            log.warn("Agent [{}] circuit breaker is OPEN, executing fallback", agentId);
            return fallback.apply(e);
        } catch (Exception e) {
            log.warn("Agent [{}] call failed after retries, executing fallback: {}", agentId, e.getMessage());
            return fallback.apply(e);
        }
    }

    /**
     * 构建降级响应消息
     * 优先使用自定义 FallbackHandler，否则返回 ERROR 类型消息
     *
     * @param toAgentId 目标 Agent ID
     * @param payload   原始请求 payload
     * @param cause     失败原因
     * @return 降级响应消息
     */
    private Message buildFallbackResponse(String toAgentId, Map<String, Object> payload, Throwable cause) {
        if (fallbackHandler != null) {
            // 调用自定义降级处理器，将结果封装为 Message
            Map<String, Object> fallbackPayload = fallbackHandler.handleRequestFallback(toAgentId, payload, cause);
            log.debug("自定义降级处理器返回结果: agentId={}, payloadKeys={}", toAgentId, fallbackPayload.keySet());
        }
        // 默认降级：返回 ERROR 类型消息
        return Message.newBuilder()
            .setMessageId(UUID.randomUUID().toString())
            .setToAgentId(toAgentId)
            .setType(MessageType.ERROR)
            .build();
    }

    /**
     * 获取所有熔断器的当前状态（用于监控）
     *
     * @return agentId -> 熔断器状态 的映射
     */
    public Map<String, String> getCircuitBreakerStates() {
        return cbConfig.getAllCircuitBreakerStates();
    }

    /**
     * 手动重置指定 Agent 的熔断器
     * 适用于 Agent 恢复后强制关闭熔断器，无需等待自动恢复
     *
     * @param agentId 目标 Agent ID
     */
    public void resetCircuitBreaker(String agentId) {
        cbConfig.resetCircuitBreaker(agentId);
    }
}
