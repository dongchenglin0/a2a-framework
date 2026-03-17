package com.fox.a2a.registry.persistence;

import com.fox.a2a.proto.Message;
import com.google.protobuf.util.JsonFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息持久化服务（基于 Redis Stream）
 * 仅在 a2a.persistence.enabled=true 时启用
 *
 * 核心功能：
 * 1. PubSub 消息持久化：将 PubSub 消息写入 Redis Stream，防止 Agent 重启后消息丢失
 * 2. 消息重放：Agent 重新上线后可从指定 offset 消费历史消息
 * 3. 消息 TTL 控制：通过 MAXLEN 限制 Stream 长度，避免 Redis 内存无限增长
 *
 * Redis Stream Key 格式：a2a:stream:{topic}
 *
 * 使用示例：
 * <pre>
 * // 持久化消息
 * persistenceService.persistMessage("order-events", message);
 *
 * // Agent 重新上线后，从头消费历史消息
 * List&lt;Map&lt;String, String&gt;&gt; history = persistenceService.readMessages("order-events", "0", 100);
 *
 * // 只消费最新消息（从当前时间点之后）
 * List&lt;Map&lt;String, String&gt;&gt; newMsgs = persistenceService.readMessages("order-events", "$", 10);
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "a2a.persistence.enabled", havingValue = "true")
public class MessagePersistenceService {

    private final RedisTemplate<String, String> stringRedisTemplate;
    private final PersistenceProperties properties;

    /** Redis Stream Key 前缀 */
    private static final String STREAM_PREFIX = "a2a:stream:";

    /**
     * 持久化 PubSub 消息到 Redis Stream
     * 写入后自动修剪 Stream 长度，防止内存溢出
     *
     * @param topic   PubSub topic 名称
     * @param message 待持久化的消息对象
     */
    public void persistMessage(String topic, Message message) {
        // 检查是否需要持久化该 topic
        if (!shouldPersist(topic)) {
            return;
        }

        try {
            String streamKey = STREAM_PREFIX + topic;
            // 将 Protobuf Message 序列化为 JSON 字符串
            String messageJson = JsonFormat.printer()
                .includingDefaultValueFields()
                .print(message);

            // 构建 Stream 字段
            Map<String, String> fields = new HashMap<>();
            fields.put("messageId", message.getMessageId());
            fields.put("fromAgentId", message.getFromAgentId());
            fields.put("toAgentId", message.getToAgentId());
            fields.put("topic", topic);
            fields.put("payload", messageJson);
            fields.put("timestamp", String.valueOf(System.currentTimeMillis()));

            // 写入 Redis Stream（自动生成 Stream ID）
            stringRedisTemplate.opsForStream().add(
                StreamRecords.newRecord()
                    .in(streamKey)
                    .ofMap(fields)
            );

            // 修剪 Stream 长度（保留最新的 maxMessagesPerTopic 条）
            int maxLen = properties.getMaxMessagesPerTopic();
            stringRedisTemplate.opsForStream().trim(streamKey, maxLen);

            log.debug("消息已持久化: topic={}, messageId={}", topic, message.getMessageId());
        } catch (Exception e) {
            // 持久化失败不影响主流程，仅记录警告日志
            log.warn("消息持久化失败: topic={}, messageId={}, error={}",
                topic, message.getMessageId(), e.getMessage());
        }
    }

    /**
     * 读取指定 topic 的历史消息（用于 Agent 重新上线后消费）
     *
     * @param topic      PubSub topic 名称
     * @param fromOffset 起始 offset：
     *                   "0"  - 从 Stream 头部开始读（消费全部历史）
     *                   "$"  - 只读当前时间点之后的新消息
     *                   具体 ID - 从指定 ID 之后开始读（断点续传）
     * @param count      最多读取条数
     * @return 消息列表，每条消息包含 messageId/fromAgentId/payload/timestamp/offset 字段
     */
    public List<Map<String, String>> readMessages(String topic, String fromOffset, int count) {
        try {
            String streamKey = STREAM_PREFIX + topic;
            List<MapRecord<String, Object, Object>> records =
                stringRedisTemplate.opsForStream().read(
                    StreamOffset.create(streamKey, ReadOffset.from(fromOffset)),
                    StreamReadOptions.empty().count(count)
                );

            if (records == null || records.isEmpty()) {
                return List.of();
            }

            List<Map<String, String>> result = new ArrayList<>(records.size());
            for (MapRecord<String, Object, Object> record : records) {
                Map<String, String> msg = new HashMap<>();
                // 将 Stream 字段转换为 String Map
                record.getValue().forEach((k, v) -> msg.put(k.toString(), v.toString()));
                // 附加 Stream offset（用于断点续传）
                msg.put("offset", record.getId().getValue());
                result.add(msg);
            }

            log.debug("读取历史消息: topic={}, fromOffset={}, count={}, actual={}",
                topic, fromOffset, count, result.size());
            return result;
        } catch (Exception e) {
            log.warn("读取历史消息失败: topic={}, fromOffset={}, error={}", topic, fromOffset, e.getMessage());
            return List.of();
        }
    }

    /**
     * 获取指定 topic 的当前消息数量
     *
     * @param topic PubSub topic 名称
     * @return 消息数量，查询失败时返回 0
     */
    public long getMessageCount(String topic) {
        try {
            Long size = stringRedisTemplate.opsForStream().size(STREAM_PREFIX + topic);
            return size != null ? size : 0;
        } catch (Exception e) {
            log.warn("获取消息数量失败: topic={}, error={}", topic, e.getMessage());
            return 0;
        }
    }

    /**
     * 清理过期 topic（删除超过 retentionDays 天未活跃的 Stream）
     * 建议通过 @Scheduled 定时调用，如每天凌晨执行一次
     *
     * @param retentionDays 保留天数，超过此天数的 Stream 将被删除
     */
    public void cleanupExpiredTopics(int retentionDays) {
        log.info("开始清理过期 topic（保留 {} 天）", retentionDays);
        try {
            // 通过 Redis SCAN 遍历所有 a2a:stream:* key
            // 检查最后写入时间（Stream 最后一条消息的 ID 包含时间戳）
            // 生产环境建议使用 Lua 脚本原子执行，避免大量 key 扫描阻塞 Redis
            long cutoffMs = System.currentTimeMillis() - (long) retentionDays * 24 * 3600 * 1000;
            log.debug("清理截止时间戳: {}", cutoffMs);
            // TODO: 实现 SCAN + 时间戳检查逻辑（需要 Redis 6.2+ 的 XINFO STREAM 命令）
        } catch (Exception e) {
            log.warn("清理过期 topic 失败: {}", e.getMessage());
        }
    }

    /**
     * 判断指定 topic 是否需要持久化
     * 若 persistTopics 为空，则持久化所有 topic；否则只持久化列表中的 topic
     *
     * @param topic PubSub topic 名称
     * @return true 表示需要持久化
     */
    private boolean shouldPersist(String topic) {
        List<String> persistTopics = properties.getPersistTopics();
        if (persistTopics == null || persistTopics.isEmpty()) {
            return true; // 空列表 = 持久化所有 topic
        }
        return persistTopics.contains(topic);
    }
}
