package com.fox.a2a.registry.store;

import com.fox.a2a.proto.AgentInfo;
import com.fox.a2a.proto.AgentStatus;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 基于 Redis 的 Agent 存储实现
 * 使用 protobuf 二进制序列化存储 Agent 信息
 * 当 store-mode=redis 时启用
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "a2a.registry.store-mode", havingValue = "redis")
public class RedisAgentStore implements AgentStore {

    /**
     * Redis key 前缀：Agent 信息
     */
    private static final String AGENT_KEY_PREFIX = "a2a:agent:";

    /**
     * Redis key 前缀：心跳时间戳
     */
    private static final String HEARTBEAT_KEY_PREFIX = "a2a:heartbeat:";

    /**
     * Redis Set key：所有 Agent ID 的索引
     */
    private static final String AGENTS_INDEX_KEY = "a2a:agents:all";

    /**
     * RedisTemplate，key 为 String，value 为 byte[]（protobuf 二进制）
     */
    private final RedisTemplate<String, byte[]> redisTemplate;

    /**
     * 构建 Agent 信息的 Redis key
     */
    private String agentKey(String agentId) {
        return AGENT_KEY_PREFIX + agentId;
    }

    /**
     * 构建心跳时间戳的 Redis key
     */
    private String heartbeatKey(String agentId) {
        return HEARTBEAT_KEY_PREFIX + agentId;
    }

    /**
     * 保存 Agent 信息到 Redis
     * 同时将 agentId 加入全局索引 Set
     */
    @Override
    public void save(AgentInfo agentInfo) {
        try {
            String agentId = agentInfo.getAgentId();
            // 序列化 protobuf 对象为字节数组
            byte[] bytes = agentInfo.toByteArray();
            redisTemplate.opsForValue().set(agentKey(agentId), bytes);
            // 加入全局索引
            redisTemplate.opsForSet().add(AGENTS_INDEX_KEY, agentId.getBytes());
            // 初始化心跳（若不存在）
            if (Boolean.FALSE.equals(redisTemplate.hasKey(heartbeatKey(agentId)))) {
                String ts = String.valueOf(System.currentTimeMillis());
                redisTemplate.opsForValue().set(heartbeatKey(agentId), ts.getBytes());
            }
            log.debug("Redis 保存 Agent: {}", agentId);
        } catch (Exception e) {
            log.error("Redis 保存 Agent 失败: {}", e.getMessage(), e);
            throw new RuntimeException("保存 Agent 失败", e);
        }
    }

    /**
     * 根据 agentId 从 Redis 查询 Agent
     */
    @Override
    public Optional<AgentInfo> findById(String agentId) {
        try {
            byte[] bytes = redisTemplate.opsForValue().get(agentKey(agentId));
            if (bytes == null) {
                return Optional.empty();
            }
            return Optional.of(AgentInfo.parseFrom(bytes));
        } catch (InvalidProtocolBufferException e) {
            log.error("反序列化 Agent [{}] 失败: {}", agentId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * 查询所有 Agent
     * 通过全局索引 Set 获取所有 agentId，再逐一查询
     */
    @Override
    public List<AgentInfo> findAll() {
        List<AgentInfo> result = new ArrayList<>();
        Set<byte[]> agentIdBytes = redisTemplate.opsForSet().members(AGENTS_INDEX_KEY);
        if (agentIdBytes == null) {
            return result;
        }
        for (byte[] idBytes : agentIdBytes) {
            String agentId = new String(idBytes);
            findById(agentId).ifPresent(result::add);
        }
        return result;
    }

    /**
     * 按类型查询 Agent
     */
    @Override
    public List<AgentInfo> findByType(String agentType) {
        if (agentType == null || agentType.isEmpty()) {
            return findAll();
        }
        return findAll().stream()
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
        return findAll().stream()
                .filter(agent -> agent.getCapabilitiesList().stream()
                        .anyMatch(capabilities::contains))
                .collect(Collectors.toList());
    }

    /**
     * 按状态查询 Agent
     */
    @Override
    public List<AgentInfo> findByStatus(AgentStatus status) {
        return findAll().stream()
                .filter(agent -> agent.getStatus() == status)
                .collect(Collectors.toList());
    }

    /**
     * 更新 Agent 状态
     * 先查询现有数据，再用 mergeFrom 更新状态字段
     */
    @Override
    public void updateStatus(String agentId, AgentStatus status) {
        findById(agentId).ifPresent(existing -> {
            AgentInfo updated = AgentInfo.newBuilder()
                    .mergeFrom(existing)
                    .setStatus(status)
                    .build();
            redisTemplate.opsForValue().set(agentKey(agentId), updated.toByteArray());
            log.debug("Redis 更新 Agent [{}] 状态: {} -> {}", agentId, existing.getStatus(), status);
        });
    }

    /**
     * 更新 Agent 心跳时间戳
     */
    @Override
    public void updateHeartbeat(String agentId, long timestampMillis) {
        String ts = String.valueOf(timestampMillis);
        redisTemplate.opsForValue().set(heartbeatKey(agentId), ts.getBytes());
        log.debug("Redis 更新 Agent [{}] 心跳时间戳: {}", agentId, timestampMillis);
    }

    /**
     * 获取 Agent 最后心跳时间戳
     * 不存在则返回 0
     */
    @Override
    public long getLastHeartbeat(String agentId) {
        byte[] bytes = redisTemplate.opsForValue().get(heartbeatKey(agentId));
        if (bytes == null) {
            return 0L;
        }
        try {
            return Long.parseLong(new String(bytes));
        } catch (NumberFormatException e) {
            log.warn("解析 Agent [{}] 心跳时间戳失败", agentId);
            return 0L;
        }
    }

    /**
     * 从 Redis 移除 Agent 及其心跳记录和索引
     */
    @Override
    public void remove(String agentId) {
        redisTemplate.delete(agentKey(agentId));
        redisTemplate.delete(heartbeatKey(agentId));
        redisTemplate.opsForSet().remove(AGENTS_INDEX_KEY, agentId.getBytes());
        log.debug("Redis 移除 Agent: {}", agentId);
    }

    /**
     * 判断 Agent 是否存在
     */
    @Override
    public boolean exists(String agentId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(agentKey(agentId)));
    }

    /**
     * 获取 Agent 总数
     */
    @Override
    public int count() {
        Long size = redisTemplate.opsForSet().size(AGENTS_INDEX_KEY);
        return size == null ? 0 : size.intValue();
    }
}
