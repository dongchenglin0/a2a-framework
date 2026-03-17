package com.fox.a2a.registry.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * A2A 安全配置属性
 * <p>
 * 对应 application.yml 中的 a2a.security 前缀配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "a2a.security")
public class SecurityProperties {

    /** TLS 配置 */
    private TlsProperties tls = new TlsProperties();

    /** JWT 配置 */
    private JwtSecurityProperties jwt = new JwtSecurityProperties();

    /**
     * TLS/mTLS 配置属性
     */
    @Data
    public static class TlsProperties {
        /** 是否启用 TLS（默认关闭，生产环境建议开启） */
        private boolean enabled = false;
        /** 服务端证书链文件路径（PEM 格式） */
        private String certChainFile;
        /** 服务端私钥文件路径（PEM 格式，PKCS#8） */
        private String privateKeyFile;
        /** 信任证书集合文件路径（mTLS 时使用，PEM 格式） */
        private String trustCertCollectionFile;
        /** 是否启用双向 mTLS（默认关闭） */
        private boolean mutualTls = false;
    }

    /**
     * JWT 安全配置属性
     */
    @Data
    public static class JwtSecurityProperties {
        /**
         * JWT 签名密钥
         * 生产环境必须通过环境变量 A2A_JWT_SECRET 覆盖，长度至少 32 字符
         */
        private String secret = "a2a-default-secret-change-in-production-must-be-32-chars";
        /** Token 有效期（小时，默认 24 小时） */
        private int expirationHours = 24;
        /** Token 签发者标识 */
        private String issuer = "a2a-registry";
        /**
         * 已撤销的 Token jti 列表（内存黑名单）
         * 生产环境建议启用 Redis 存储，此列表仅作为降级方案
         * 注意：使用 ArrayList 而非 List.of()，以支持运行时动态添加
         */
        private List<String> revokedTokenIds = new ArrayList<>();
        /**
         * 是否启用 Token 黑名单检查（默认关闭）
         * 开启后每次验证 Token 都会检查黑名单，有一定性能开销
         */
        private boolean enableRevocationCheck = false;
    }
}
