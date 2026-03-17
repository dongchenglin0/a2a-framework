package com.fox.a2a.registry.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Registry 服务配置属性
 * 对应配置前缀: a2a.registry
 */
@Data
@Component
@ConfigurationProperties(prefix = "a2a.registry")
public class RegistryProperties {

    /**
     * 存储模式: memory（内存）或 redis
     */
    private String storeMode = "memory";

    /**
     * 心跳超时秒数，超过此时间未收到心跳则标记为 OFFLINE
     */
    private int heartbeatTimeoutSeconds = 30;

    /**
     * 清理离线 Agent 的间隔秒数
     */
    private int cleanupIntervalSeconds = 60;
}
