package com.fox.a2a.registry.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置属性
 * 对应配置前缀: a2a.jwt
 */
@Data
@Component
@ConfigurationProperties(prefix = "a2a.jwt")
public class JwtProperties {

    /**
     * JWT 签名密钥（生产环境必须修改，至少 32 字符）
     */
    private String secret = "a2a-default-secret-change-in-production-must-be-32-chars";

    /**
     * Token 有效期（小时）
     */
    private int expirationHours = 24;

    /**
     * JWT 签发者
     */
    private String issuer = "a2a-registry";
}
