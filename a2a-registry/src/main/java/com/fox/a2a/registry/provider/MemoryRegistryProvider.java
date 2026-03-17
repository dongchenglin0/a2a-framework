package com.fox.a2a.registry.provider;

import com.fox.a2a.proto.AgentInfo;
import com.fox.a2a.proto.AgentStatus;
import com.fox.a2a.registry.store.AgentStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 内存注册中心实现
 * 基于 {@link AgentStore}（InMemoryAgentStore），适用于单机/开发/测试环境
 *
 * <p>激活条件：配置 a2a.registry.provider=memory（默认激活）
 *
 * <p>特点：
 * <ul>
 *   <li>零依赖，开箱即用</li>
 *   <li>数据存储在 JVM 内存中，重启后丢失</li>
 *   <li>不支持多实例共享，仅适合单节点部署</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "a2a.registry.provider", havingValue = "memory", matchIfMissing = true)
public class MemoryRegistryProvider extends AbstractRegistryProvider {

    /** 底层存储，委托给 InMemoryAgentStore */
    private final AgentStore agentStore;

    @Override
    public String getName() {
        return "memory";
    }

    /**
     * 内存实现始终健康（无外部依赖）
     */
    @Override
    public boolean isHealthy() {
        return true;
    }

    /**
     * 注册 Agent
     * 保存 AgentInfo 并初始化心跳时间，广播注册事件
     */
    @Override
    public void register(AgentInfo agentInfo) {
        agentStore.save(agentInfo);
        agentStore.updateHeartbeat(agentInfo.getAgentId(), System.currentTimeMillis());
        log.info("Agent 已注册: agentId={}, type={}", agentInfo.getAgentId(), agentInfo.getAgentType());
        fireAgentRegistered(agentInfo);
    }

    /**
     * 注销 Agent
     * 从存储中移除，广播注销事件
     */
    @Override
    public void deregister(String agentId) {
        agentStore.remove(agentId);
        log.info("Agent 已注销: agentId={}", agentId);
        fireAgentDeregistered(agentId);
    }

    /**
     * 心跳更新
     * 更新心跳时间戳和状态
     */
    @Override
    public void heartbeat(String agentId, AgentStatus status, long timestampMillis) {
        agentStore.updateHeartbeat(agentId, timestampMillis);
        agentStore.updateStatus(agentId, status);
        log.debug("Agent 心跳更新: agentId={}, status={}", agentId, status);
    }

    @Override
    public Optional<AgentInfo> findById(String agentId) {
        return agentStore.findById(agentId);
    }

    @Override
    public List<AgentInfo> findAll() {
        return agentStore.findAll();
    }

    @Override
    public List<AgentInfo> findByType(String agentType) {
        return agentStore.findByType(agentType);
    }

    @Override
    public List<AgentInfo> findByCapabilities(List<String> capabilities) {
        return agentStore.findByCapabilities(capabilities);
    }

    @Override
    public List<AgentInfo> findByStatus(AgentStatus status) {
        return agentStore.findByStatus(status);
    }

    /**
     * 发现可用 Agent
     * 按类型或能力过滤，只返回 ONLINE 状态的 Agent，支持 limit 限制
     */
    @Override
    public List<AgentInfo> discover(String agentType, List<String> capabilities, int limit) {
        List<AgentInfo> result;

        // 优先按类型过滤
        if (agentType != null && !agentType.isEmpty()) {
            result = agentStore.findByType(agentType);
        } else if (capabilities != null && !capabilities.isEmpty()) {
            // 其次按能力过滤
            result = agentStore.findByCapabilities(capabilities);
        } else {
            // 无过滤条件则返回全部
            result = agentStore.findAll();
        }

        // 只返回 ONLINE 状态的 Agent
        result = result.stream()
                .filter(a -> a.getStatus() == AgentStatus.ONLINE)
                .collect(Collectors.toList());

        // 应用 limit 限制
        if (limit > 0 && result.size() > limit) {
            result = result.subList(0, limit);
        }

        return result;
    }

    /**
     * 更新 Agent 状态
     * 先查询旧状态，更新后广播状态变更事件
     */
    @Override
    public void updateStatus(String agentId, AgentStatus status) {
        Optional<AgentInfo> existing = agentStore.findById(agentId);
        existing.ifPresent(old -> {
            AgentStatus oldStatus = old.getStatus();
            agentStore.updateStatus(agentId, status);
            log.info("Agent 状态更新: agentId={}, {} -> {}", agentId, oldStatus, status);
            fireAgentStatusChanged(agentId, oldStatus, status);
        });
    }

    @Override
    public long getLastHeartbeat(String agentId) {
        return agentStore.getLastHeartbeat(agentId);
    }

    @Override
    public boolean exists(String agentId) {
        return agentStore.exists(agentId);
    }

    @Override
    public int count() {
        return agentStore.count();
    }
}
