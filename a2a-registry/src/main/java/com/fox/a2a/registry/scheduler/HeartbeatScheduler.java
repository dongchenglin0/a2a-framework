package com.fox.a2a.registry.scheduler;

import com.fox.a2a.proto.AgentInfo;
import com.fox.a2a.proto.AgentStatus;
import com.fox.a2a.registry.config.RegistryProperties;
import com.fox.a2a.registry.store.AgentStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 心跳调度器
 * 定期检查 Agent 心跳状态，超时则标记为 OFFLINE；
 * 定期清理长时间离线的 Agent
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatScheduler {

    private final AgentStore agentStore;
    private final RegistryProperties registryProperties;

    /**
     * 离线 Agent 清理阈值（毫秒）：超过 5 分钟的 OFFLINE Agent 将被清理
     */
    private static final long CLEANUP_THRESHOLD_MS = 5 * 60 * 1000L;

    /**
     * 检查所有 ONLINE Agent 的心跳状态
     * 超过 heartbeatTimeoutSeconds 未收到心跳则标记为 OFFLINE
     * 默认每 10 秒执行一次
     */
    @Scheduled(fixedDelayString = "${a2a.registry.heartbeat-check-interval-ms:10000}")
    public void checkHeartbeats() {
        try {
            // 获取所有 ONLINE 状态的 Agent
            List<AgentInfo> onlineAgents = agentStore.findByStatus(AgentStatus.ONLINE);
            if (onlineAgents.isEmpty()) {
                return;
            }

            long now = System.currentTimeMillis();
            // 心跳超时阈值（毫秒）
            long timeoutMs = registryProperties.getHeartbeatTimeoutSeconds() * 1000L;

            int offlineCount = 0;
            for (AgentInfo agent : onlineAgents) {
                String agentId = agent.getAgentId();
                long lastHeartbeat = agentStore.getLastHeartbeat(agentId);

                // 检查是否超时
                if (lastHeartbeat > 0 && (now - lastHeartbeat) > timeoutMs) {
                    agentStore.updateStatus(agentId, AgentStatus.OFFLINE);
                    offlineCount++;
                    log.warn("Agent [{}] 心跳超时（最后心跳: {}ms 前），标记为 OFFLINE",
                            agentId, now - lastHeartbeat);
                }
            }

            if (offlineCount > 0) {
                log.info("心跳检查完成，共 {} 个 Agent 标记为 OFFLINE", offlineCount);
            } else {
                log.debug("心跳检查完成，所有 {} 个 ONLINE Agent 心跳正常", onlineAgents.size());
            }

        } catch (Exception e) {
            log.error("心跳检查异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 清理长时间离线的 Agent
     * 超过 5 分钟的 OFFLINE Agent 将被从存储中移除
     * 默认每 60 秒执行一次
     */
    @Scheduled(fixedDelayString = "${a2a.registry.cleanup-interval-ms:60000}")
    public void cleanupOfflineAgents() {
        try {
            // 获取所有 OFFLINE 状态的 Agent
            List<AgentInfo> offlineAgents = agentStore.findByStatus(AgentStatus.OFFLINE);
            if (offlineAgents.isEmpty()) {
                return;
            }

            long now = System.currentTimeMillis();
            int cleanedCount = 0;

            for (AgentInfo agent : offlineAgents) {
                String agentId = agent.getAgentId();
                long lastHeartbeat = agentStore.getLastHeartbeat(agentId);

                // 超过 5 分钟未有心跳则清理
                if (lastHeartbeat == 0 || (now - lastHeartbeat) > CLEANUP_THRESHOLD_MS) {
                    agentStore.remove(agentId);
                    cleanedCount++;
                    log.info("清理长时间离线 Agent: {}（最后心跳: {}ms 前）",
                            agentId, lastHeartbeat > 0 ? now - lastHeartbeat : -1);
                }
            }

            if (cleanedCount > 0) {
                log.info("离线 Agent 清理完成，共清理 {} 个，当前 Agent 总数: {}",
                        cleanedCount, agentStore.count());
            } else {
                log.debug("离线 Agent 清理检查完成，无需清理，当前 OFFLINE Agent 数: {}",
                        offlineAgents.size());
            }

        } catch (Exception e) {
            log.error("清理离线 Agent 异常: {}", e.getMessage(), e);
        }
    }
}
