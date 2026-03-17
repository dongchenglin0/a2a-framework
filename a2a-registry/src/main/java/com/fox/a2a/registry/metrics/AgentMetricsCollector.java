package com.fox.a2a.registry.metrics;

import com.fox.a2a.proto.AgentStatus;
import com.fox.a2a.registry.provider.RegistryProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Agent 状态指标定时收集器
 * 每30秒更新一次 Gauge 指标（在线/离线 Agent 数量等）
 * <p>
 * 依赖 @EnableScheduling（在主启动类或配置类上开启）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentMetricsCollector {

    private final RegistryProvider registryProvider;
    private final A2AMetrics metrics;

    /**
     * 定时收集 Agent 状态指标
     * fixedDelay = 30000ms，即上次执行完成后 30 秒再次执行
     */
    @Scheduled(fixedDelay = 30000)
    public void collectAgentMetrics() {
        try {
            // 查询各状态 Agent 数量
            int onlineCount = registryProvider.findByStatus(AgentStatus.ONLINE).size();
            int offlineCount = registryProvider.findByStatus(AgentStatus.OFFLINE).size();
            int totalCount = registryProvider.count();

            // 更新 Gauge 指标
            metrics.setOnlineAgentCount(onlineCount);
            metrics.setOfflineAgentCount(offlineCount);

            log.debug("Agent 指标收集完成: 在线={}, 离线={}, 总计={}", onlineCount, offlineCount, totalCount);
        } catch (Exception e) {
            // 收集失败不影响主流程，仅记录警告
            log.warn("Agent 指标收集失败: {}", e.getMessage());
        }
    }
}
