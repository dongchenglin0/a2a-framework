package com.fox.a2a.core.resilience;

import java.util.Map;

/**
 * 降级处理器接口
 * 当目标 Agent 不可用（熔断器打开）时，调用此接口的实现执行降级逻辑。
 *
 * 典型降级策略：
 * 1. 返回缓存数据（Cache Fallback）
 * 2. 调用备用 Agent（Backup Agent Fallback）
 * 3. 返回默认值（Default Value Fallback）
 * 4. 本地降级处理（Local Processing Fallback）
 *
 * 使用示例：
 * <pre>
 * ResilientA2AClient client = new ResilientA2AClient(config, (toAgentId, payload, cause) -> {
 *     // 从缓存返回上次成功的结果
 *     return cacheService.getLastResult(toAgentId);
 * });
 * </pre>
 */
public interface FallbackHandler {

    /**
     * 请求降级处理
     * 当目标 Agent 的熔断器打开或调用超时时触发
     *
     * @param toAgentId 目标 Agent ID
     * @param payload   原始请求 payload
     * @param cause     失败原因（CallNotPermittedException 或其他异常）
     * @return 降级响应 payload，不能为 null
     */
    Map<String, Object> handleRequestFallback(String toAgentId, Map<String, Object> payload, Throwable cause);

    /**
     * 任务委托降级处理
     * 当任务委托因熔断器打开而失败时触发
     * 默认实现直接抛出异常，子类可覆盖以实现本地任务执行等降级策略
     *
     * @param toAgentId 目标 Agent ID
     * @param taskType  任务类型
     * @param input     任务输入参数
     * @param cause     失败原因
     * @return 降级任务 ID（可以是本地执行的任务 ID 或占位 ID）
     */
    default String handleTaskFallback(String toAgentId, String taskType,
                                      Map<String, Object> input, Throwable cause) {
        throw new RuntimeException(
            "Agent [" + toAgentId + "] 不可用，任务委托失败: " + cause.getMessage(), cause);
    }
}
