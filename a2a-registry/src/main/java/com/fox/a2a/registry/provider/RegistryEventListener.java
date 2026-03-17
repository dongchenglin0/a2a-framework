package com.fox.a2a.registry.provider;

import com.fox.a2a.proto.AgentInfo;
import com.fox.a2a.proto.AgentStatus;

/**
 * 注册中心事件监听器接口
 * 用于监听 Agent 注册/注销/状态变更事件
 *
 * <p>使用场景：
 * <ul>
 *   <li>gRPC watchAgents 流式推送</li>
 *   <li>本地缓存同步</li>
 *   <li>监控告警触发</li>
 *   <li>Nacos/ETCD 变更推送回调</li>
 * </ul>
 */
public interface RegistryEventListener {

    /**
     * Agent 注册事件
     *
     * @param agentInfo 新注册的 Agent 信息
     */
    void onAgentRegistered(AgentInfo agentInfo);

    /**
     * Agent 注销事件
     *
     * @param agentId 已注销的 Agent ID
     */
    void onAgentDeregistered(String agentId);

    /**
     * Agent 状态变更事件
     *
     * @param agentId   Agent ID
     * @param oldStatus 变更前的状态
     * @param newStatus 变更后的状态
     */
    void onAgentStatusChanged(String agentId, AgentStatus oldStatus, AgentStatus newStatus);
}
