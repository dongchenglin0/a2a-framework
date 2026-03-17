package com.fox.a2a.registry.scheduler;

import com.fox.a2a.proto.AgentInfo;
import com.fox.a2a.proto.AgentStatus;
import com.fox.a2a.registry.config.RegistryProperties;
import com.fox.a2a.registry.metrics.A2AMetrics;
import com.fox.a2a.registry.provider.RegistryProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 心跳检测定时任务（v1.1 重构版）
 * 依赖从 AgentStore 升级为 RegistryProvider
 * 新增：Prometheus 指标记录
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatScheduler {

    private final RegistryProvider registryProvider;
    private final RegistryProperties registryProperties;
    private final A2AMetrics metrics;

    /**
     * 每10秒检查一次心跳超时的 Agent
     * 超过 heartbeatTimeoutSeconds 未收到心跳，标记为 OFFLINE
     */
    @Scheduled(fixedDelayString = "${a2a.registry.heartbeat-check-interval-ms:10000}")
    public void checkHeartbeats() {
        try {
            long now = System.currentTimeMillis();
            long timeoutMs = registryProperties.getHeartbeatTimeoutSeconds() * 1000L;

            List<AgentInfo> onlineAgents = registryProvider.findByStatus(AgentStatus.ONLINE);
            int timeoutCount = 0;

            for (AgentInfo agent : onlineAgents) {
                String agentId = agent.getAgentId();
                long lastHeartbeat = registryProvider.getLastHeartbeat(agentId);

                if (lastHeartbeat > 0 && (now - lastHeartbeat) > timeoutMs) {
                    registryProvider.updateStatus(agentId, AgentStatus.OFFLINE);
                    metrics.recordHeartbeatTimeout();
                    timeoutCount++;
                    log.warn("Agent [{}] 心跳超时（{}ms），标记为 OFFLINE", agentId, now - lastHeartbeat);
                }
            }

            if (timeoutCount > 0) {
                log.info("心跳检测完成，本次超时 {} 个 Agent", timeoutCount);
            }

            // 更新在线/离线指标
            int onlineCount = registryProvider.findByStatus(AgentStatus.ONLINE).size();
            int offlineCount = registryProvider.findByStatus(AgentStatus.OFFLINE).size();
            metrics.setOnlineAgentCount(onlineCount);
            metrics.setOfflineAgentCount(offlineCount);

        } catch (Exception e) {
            log.error("心跳检测异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 每60秒清理长期 OFFLINE 的 Agent
     * 超过 5 分钟仍为 OFFLINE 的 Agent 将被彻底移除
     */
    @Scheduled(fixedDelayString = "${a2a.registry.cleanup-interval-ms:60000}")
    public void cleanupOfflineAgents() {
        try {
            long now = System.currentTimeMillis();
            long cleanupThresholdMs = 5 * 60 * 1000L; // 5分钟

            List<AgentInfo> offlineAgents = registryProvider.findByStatus(AgentStatus.OFFLINE);
            int cleanupCount = 0;

            for (AgentInfo agent : offlineAgents) {
                String agentId = agent.getAgentId();
                long lastHeartbeat = registryProvider.getLastHeartbeat(agentId);

                if (lastHeartbeat > 0 && (now - lastHeartbeat) > cleanupThresholdMs) {
                    registryProvider.deregister(agentId);
                    cleanupCount++;
                    log.info("清理长期离线 Agent: {} (离线时长: {}s)",
                            agentId, (now - lastHeartbeat) / 1000);
                }
            }

            if (cleanupCount > 0) {
                log.info("离线 Agent 清理完成，本次清理 {} 个", cleanupCount);
            }

        } catch (Exception e) {
            log.error("离线 Agent 清理异常: {}", e.getMessage(), e);
        }
    }
}
