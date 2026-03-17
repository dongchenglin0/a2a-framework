package com.fox.a2a.registry.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.ByteArrayRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置类
 * 配置 RedisTemplate 使用 String 作为 key，byte[] 作为 value（用于 protobuf 序列化）
 */
@Configuration
public class RedisConfig {

    /**
     * 配置 RedisTemplate
     * key 使用 StringRedisSerializer，value 使用 ByteArrayRedisSerializer
     * 适配 protobuf 对象的二进制序列化存储
     *
     * @param factory Redis 连接工厂
     * @return 配置好的 RedisTemplate
     */
    @Bean
    public RedisTemplate<String, byte[]> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        // key 使用字符串序列化
        template.setKeySerializer(new StringRedisSerializer());
        // value 使用字节数组序列化（存储 protobuf 二进制）
        template.setValueSerializer(new ByteArrayRedisSerializer());
        // Hash key 使用字符串序列化
        template.setHashKeySerializer(new StringRedisSerializer());
        // Hash value 使用字节数组序列化
        template.setHashValueSerializer(new ByteArrayRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
