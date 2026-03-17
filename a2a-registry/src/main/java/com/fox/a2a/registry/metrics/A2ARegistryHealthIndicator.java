package com.fox.a2a.registry.metrics;

import com.fox.a2a.proto.AgentStatus;
import com.fox.a2a.registry.provider.RegistryProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * A2A Registry 自定义健康检查
 * 暴露到 /actuator/health/a2aRegistry
 * <p>
 * Spring Boot Actuator 会自动发现所有 HealthIndicator Bean，
 * Bean 名称 "a2aRegistry" 对应路径 /actuator/health/a2aRegistry
 */
@Component("a2aRegistry")
@RequiredArgsConstructor
public class A2ARegistryHealthIndicator implements HealthIndicator {

    private final RegistryProvider registryProvider;

    @Override
    public Health health() {
        try {
            // 检查注册中心是否健康（如 Redis 连接是否正常）
            boolean healthy = registryProvider.isHealthy();
            int totalAgents = registryProvider.count();
            int onlineAgents = registryProvider.findByStatus(AgentStatus.ONLINE).size();

            if (healthy) {
                return Health.up()
                        .withDetail("provider", registryProvider.getName())
                        .withDetail("totalAgents", totalAgents)
                        .withDetail("onlineAgents", onlineAgents)
                        .build();
            } else {
                return Health.down()
                        .withDetail("provider", registryProvider.getName())
                        .withDetail("reason", "注册中心连接异常")
                        .build();
            }
        } catch (Exception e) {
            // 发生异常时返回 DOWN 状态，并附带异常信息
            return Health.down(e)
                    .withDetail("provider", registryProvider.getName())
                    .build();
        }
    }
}
