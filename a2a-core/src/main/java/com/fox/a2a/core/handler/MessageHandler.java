package com.fox.a2a.core.handler;

import java.util.Map;

/**
 * 消息处理器接口
 * Agent 业务逻辑实现此接口以处理来自其他 Agent 的消息
 */
public interface MessageHandler {

    /**
     * 处理同步请求，返回响应 payload
     * 调用方会等待此方法返回后才收到响应
     *
     * @param fromAgentId 发送方 Agent ID
     * @param messageId   消息唯一 ID
     * @param payload     请求载荷（key-value 结构）
     * @return 响应载荷（key-value 结构），不可为 null，无数据时返回空 Map
     */
    Map<String, Object> handleRequest(String fromAgentId, String messageId, Map<String, Object> payload);

    /**
     * 处理单向消息（Fire-and-Forget）
     * 调用方不等待响应，此方法无需返回值
     *
     * @param fromAgentId 发送方 Agent ID
     * @param topic       消息主题
     * @param payload     消息载荷（key-value 结构）
     */
    void handleMessage(String fromAgentId, String topic, Map<String, Object> payload);
}
