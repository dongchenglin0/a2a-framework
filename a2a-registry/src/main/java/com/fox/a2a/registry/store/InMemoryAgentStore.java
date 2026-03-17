package com.fox.a2a.registry.store;

import com.fox.a2a.proto.AgentInfo;
import com.fox.a2a.proto.AgentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 基于内存的 Agent 存储实现
 * 使用 ConcurrentHashMap 保证线程安全
 * 默认启用（store-mode=memory 或未配置时生效）
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "a2a.registry.store-mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryAgentStore implements AgentStore {

    /**
     * Agent 信息存储，key 为 agentId
     */
    private final ConcurrentHashMap<String, AgentInfo> agents = new ConcurrentHashMap<>();

    /**
     * Agent 心跳时间戳存储，key 为 agentId，value 为毫秒时间戳
     */
    private final ConcurrentHashMap<String, Long> heartbeats = new ConcurrentHashMap<>();

    /**
     * 保存 Agent 信息
     * 若已存在则覆盖
     */
    @Override
    public void save(AgentInfo agentInfo) {
        agents.put(agentInfo.getAgentId(), agentInfo);
        // 初始化心跳时间为当前时间
        heartbeats.putIfAbsent(agentInfo.getAgentId(), System.currentTimeMillis());
        log.debug("保存 Agent: {}", agentInfo.getAgentId());
    }

    /**
     * 根据 agentId 查询 Agent
     */
    @Override
    public Optional<AgentInfo> findById(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    /**
     * 查询所有 Agent
     */
    @Override
    public List<AgentInfo> findAll() {
        return new ArrayList<>(agents.values());
    }

    /**
     * 按类型查询 Agent
     */
    @Override
    public List<AgentInfo> findByType(String agentType) {
        if (agentType == null || agentType.isEmpty()) {
            return findAll();
        }
        return agents.values().stream()
                .filter(agent -> agentType.equals(agent.getAgentType()))
                .collect(Collectors.toList());
    }

    /**
     * 按能力列表查询 Agent
     * Agent 包含任意一个指定能力即匹配
     */
    @Override
    public List<AgentInfo> findByCapabilities(List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return findAll();
        }
        return agents.values().stream()
                .filter(agent -> agent.getCapabilitiesList().stream()
                        .anyMatch(capabilities::contains))
                .collect(Collectors.toList());
    }

    /**
     * 按状态查询 Agent
     */
    @Override
    public List<AgentInfo> findByStatus(AgentStatus status) {
        return agents.values().stream()
                .filter(agent -> agent.getStatus() == status)
                .collect(Collectors.toList());
    }

    /**
     * 更新 Agent 状态
     * 使用 protobuf Builder 的 mergeFrom 方式更新，保留其他字段不变
     */
    @Override
    public void updateStatus(String agentId, AgentStatus status) {
        agents.computeIfPresent(agentId, (id, existing) -> {
            AgentInfo updated = AgentInfo.newBuilder()
                    .mergeFrom(existing)
                    .setStatus(status)
                    .build();
            log.debug("更新 Agent [{}] 状态: {} -> {}", agentId, existing.getStatus(), status);
            return updated;
        });
    }

    /**
     * 更新 Agent 心跳时间戳
     */
    @Override
    public void updateHeartbeat(String agentId, long timestampMillis) {
        heartbeats.put(agentId, timestampMillis);
        log.debug("更新 Agent [{}] 心跳时间戳: {}", agentId, timestampMillis);
    }

    /**
     * 获取 Agent 最后心跳时间戳
     * 不存在则返回 0
     */
    @Override
    public long getLastHeartbeat(String agentId) {
        return heartbeats.getOrDefault(agentId, 0L);
    }

    /**
     * 移除 Agent 及其心跳记录
     */
    @Override
    public void remove(String agentId) {
        agents.remove(agentId);
        heartbeats.remove(agentId);
        log.debug("移除 Agent: {}", agentId);
    }

    /**
     * 判断 Agent 是否存在
     */
    @Override
    public boolean exists(String agentId) {
        return agents.containsKey(agentId);
    }

    /**
     * 获取 Agent 总数
     */
    @Override
    public int count() {
        return agents.size();
    }
}
