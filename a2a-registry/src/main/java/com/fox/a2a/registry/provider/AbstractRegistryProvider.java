package com.fox.a2a.registry.provider;

import com.fox.a2a.proto.AgentInfo;
import com.fox.a2a.proto.AgentStatus;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 注册中心抽象基类
 * 提供事件监听器管理、公共日志等公共逻辑
 * 子类只需实现核心存储操作，无需关心事件广播细节
 *
 * <p>设计原则：
 * <ul>
 *   <li>事件监听器列表使用 {@link CopyOnWriteArrayList} 保证并发安全</li>
 *   <li>事件广播时捕获单个监听器异常，不影响其他监听器执行</li>
 *   <li>initialize/destroy 提供默认日志实现，子类可调用 super 后追加逻辑</li>
 * </ul>
 */
@Slf4j
public abstract class AbstractRegistryProvider implements RegistryProvider {

    /**
     * 事件监听器列表（线程安全）
     * 使用 CopyOnWriteArrayList 支持并发读写，适合读多写少场景
     */
    protected final List<RegistryEventListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 注册事件监听器
     *
     * @param listener 事件监听器
     */
    @Override
    public void addListener(RegistryEventListener listener) {
        listeners.add(listener);
        log.debug("注册事件监听器: {}", listener.getClass().getSimpleName());
    }

    /**
     * 移除事件监听器
     *
     * @param listener 事件监听器
     */
    @Override
    public void removeListener(RegistryEventListener listener) {
        listeners.remove(listener);
        log.debug("移除事件监听器: {}", listener.getClass().getSimpleName());
    }

    /**
     * 广播 Agent 注册事件
     * 遍历所有监听器，捕获单个异常不影响其他监听器
     *
     * @param agentInfo 已注册的 Agent 信息
     */
    protected void fireAgentRegistered(AgentInfo agentInfo) {
        if (listeners.isEmpty()) {
            return;
        }
        listeners.forEach(l -> {
            try {
                l.onAgentRegistered(agentInfo);
            } catch (Exception e) {
                log.warn("事件监听器 [{}] 处理 onAgentRegistered 异常: {}",
                        l.getClass().getSimpleName(), e.getMessage());
            }
        });
    }

    /**
     * 广播 Agent 注销事件
     * 遍历所有监听器，捕获单个异常不影响其他监听器
     *
     * @param agentId 已注销的 Agent ID
     */
    protected void fireAgentDeregistered(String agentId) {
        if (listeners.isEmpty()) {
            return;
        }
        listeners.forEach(l -> {
            try {
                l.onAgentDeregistered(agentId);
            } catch (Exception e) {
                log.warn("事件监听器 [{}] 处理 onAgentDeregistered 异常: {}",
                        l.getClass().getSimpleName(), e.getMessage());
            }
        });
    }

    /**
     * 广播 Agent 状态变更事件
     * 遍历所有监听器，捕获单个异常不影响其他监听器
     *
     * @param agentId   Agent ID
     * @param oldStatus 变更前状态
     * @param newStatus 变更后状态
     */
    protected void fireAgentStatusChanged(String agentId, AgentStatus oldStatus, AgentStatus newStatus) {
        if (listeners.isEmpty()) {
            return;
        }
        listeners.forEach(l -> {
            try {
                l.onAgentStatusChanged(agentId, oldStatus, newStatus);
            } catch (Exception e) {
                log.warn("事件监听器 [{}] 处理 onAgentStatusChanged 异常: {}",
                        l.getClass().getSimpleName(), e.getMessage());
            }
        });
    }

    /**
     * 初始化注册中心
     * 子类覆盖时建议调用 super.initialize() 保留日志输出
     */
    @Override
    public void initialize() {
        log.info("注册中心初始化: provider={}", getName());
    }

    /**
     * 销毁注册中心
     * 清空所有监听器，子类覆盖时建议调用 super.destroy()
     */
    @Override
    public void destroy() {
        log.info("注册中心销毁: provider={}", getName());
        listeners.clear();
    }
}
