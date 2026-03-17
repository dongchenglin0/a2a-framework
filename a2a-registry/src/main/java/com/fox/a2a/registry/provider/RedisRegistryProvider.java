package com.fox.a2a.registry.provider;

import com.fox.a2a.proto.AgentInfo;
import com.fox.a2a.proto.AgentStatus;
import com.fox.a2a.registry.store.AgentStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Redis 注册中心实现
 * 基于 {@link AgentStore}（RedisAgentStore），适用于分布式/生产环境
 *
 * <p>激活条件：配置 a2a.registry.provider=redis
 *
 * <p>特点：
 * <ul>
 *   <li>数据持久化到 Redis，支持多实例共享</li>
 *   <li>支持 Redis 集群和哨兵模式</li>
 *   <li>通过 PING 命令检测 Redis 连通性</li>
 *   <li>适合生产环境多节点部署</li>
 * </ul>
 *
 * <p>配置示例：
 * <pre>
 * a2a.registry.provider=redis
 * spring.redis.host=localhost
 * spring.redis.port=6379
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "a2a.registry.provider", havingValue = "redis")
public class RedisRegistryProvider extends AbstractRegistryProvider {

    /** 底层存储，委托给 RedisAgentStore */
    private final AgentStore agentStore;

    /** Redis 模板，用于健康检查 */
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public String getName() {
        return "redis";
    }

    /**
     * 通过 PING 命令检测 Redis 连通性
     * 若 Redis 连接异常则返回 false
     */
    @Override
    public boolean isHealthy() {
        try {
            RedisConnection connection = redisTemplate.getConnectionFactory().getConnection();
            String pong = connection.ping();
            connection.close();
            return "PONG".equalsIgnoreCase(pong);
        } catch (Exception e) {
            log.warn("Redis 健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 注册 Agent
     * 保存 AgentInfo 并初始化心跳时间，广播注册事件
     */
    @Override
    public void register(AgentInfo agentInfo) {
        agentStore.save(agentInfo);
        agentStore.updateHeartbeat(agentInfo.getAgentId(), System.currentTimeMillis());
        log.info("Agent 已注册（Redis）: agentId={}, type={}", agentInfo.getAgentId(), agentInfo.getAgentType());
        fireAgentRegistered(agentInfo);
    }

    /**
     * 注销 Agent
     * 从 Redis 中移除，广播注销事件
     */
    @Override
    public void deregister(String agentId) {
        agentStore.remove(agentId);
        log.info("Agent 已注销（Redis）: agentId={}", agentId);
        fireAgentDeregistered(agentId);
    }

    /**
     * 心跳更新
     * 更新 Redis 中的心跳时间戳和状态
     */
    @Override
    public void heartbeat(String agentId, AgentStatus status, long timestampMillis) {
        agentStore.updateHeartbeat(agentId, timestampMillis);
        agentStore.updateStatus(agentId, status);
        log.debug("Agent 心跳更新（Redis）: agentId={}, status={}", agentId, status);
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
            log.info("Agent 状态更新（Redis）: agentId={}, {} -> {}", agentId, oldStatus, status);
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
