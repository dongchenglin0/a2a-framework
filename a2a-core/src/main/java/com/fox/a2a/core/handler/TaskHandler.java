package com.fox.a2a.core.handler;

import java.util.Map;

/**
 * 任务处理器接口
 * Agent 业务逻辑实现此接口以处理来自其他 Agent 的任务委托
 */
public interface TaskHandler {

    /**
     * 执行委托任务，返回任务结果 payload
     * 此方法在独立线程中异步执行，不阻塞 gRPC 响应
     *
     * @param taskId           任务唯一 ID（由 AgentServer 生成）
     * @param taskType         任务类型（由委托方指定）
     * @param delegatorAgentId 委托方 Agent ID
     * @param input            任务输入参数（key-value 结构）
     * @return 任务执行结果（key-value 结构），不可为 null，无数据时返回空 Map
     */
    Map<String, Object> handleTask(String taskId, String taskType, String delegatorAgentId, Map<String, Object> input);
}
