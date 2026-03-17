package com.fox.a2a.registry.persistence;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 消息持久化配置属性
 * 对应 application.yml 中的 a2a.persistence.* 配置项
 *
 * 示例配置：
 * <pre>
 * a2a:
 *   persistence:
 *     enabled: true
 *     max-messages-per-topic: 10000
 *     retention-days: 7
 *     persist-topics:
 *       - order-events
 *       - payment-events
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "a2a.persistence")
public class PersistenceProperties {

    /**
     * 是否启用消息持久化
     * 默认关闭，需要显式开启
     */
    private boolean enabled = false;

    /**
     * 每个 topic 最大保留消息数
     * 超出后自动修剪（FIFO，删除最旧的消息）
     */
    private int maxMessagesPerTopic = 10000;

    /**
     * 消息保留天数
     * 超过此天数的 topic Stream 将在定时清理任务中被删除
     */
    private int retentionDays = 7;

    /**
     * 需要持久化的 topic 列表
     * 空列表表示持久化所有 topic
     * 非空时只持久化列表中的 topic
     */
    private List<String> persistTopics = List.of();
}
