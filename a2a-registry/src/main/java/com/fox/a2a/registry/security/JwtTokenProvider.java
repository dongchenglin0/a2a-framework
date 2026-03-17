package com.fox.a2a.registry.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * JWT Token 提供者（增强版 v1.1）
 * <p>
 * 新增特性：
 * - Token 黑名单（revoked token jti 列表，优先使用 Redis，降级为内存）
 * - 多租户 Token（payload 中包含 tenantId）
 * - Token 刷新（generateRefreshToken，有效期 7 天）
 * - 会话 Token（generateSessionToken，有效期 1 小时）
 * - 完整的异常处理和日志
 */
@Slf4j
@Component
public class JwtTokenProvider {

    private final SecurityProperties securityProperties;
    /** Redis 可选依赖，未配置 Redis 时自动降级为内存黑名单 */
    private final Optional<RedisTemplate<String, String>> redisTemplate;

    /** Redis 黑名单 key 前缀 */
    private static final String REVOKED_TOKEN_PREFIX = "a2a:revoked:token:";

    public JwtTokenProvider(SecurityProperties securityProperties,
                            Optional<RedisTemplate<String, String>> redisTemplate) {
        this.securityProperties = securityProperties;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 获取 HMAC-SHA 签名密钥
     * 密钥长度不足时 jjwt 会抛出异常，请确保 secret 至少 32 字符
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = securityProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 生成 Agent 认证 Token（访问令牌，默认有效期 24 小时）
     *
     * @param agentId   Agent 唯一标识
     * @param agentType Agent 类型（如 "worker", "coordinator"）
     * @return JWT Token 字符串
     */
    public String generateToken(String agentId, String agentType) {
        return generateToken(agentId, agentType, null,
                Duration.ofHours(securityProperties.getJwt().getExpirationHours()));
    }

    /**
     * 生成多租户 Token（支持自定义有效期）
     *
     * @param agentId    Agent 唯一标识
     * @param agentType  Agent 类型
     * @param tenantId   租户 ID（可为 null，表示默认租户）
     * @param expiration Token 有效期
     * @return JWT Token 字符串
     */
    public String generateToken(String agentId, String agentType, String tenantId, Duration expiration) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expiration.toMillis());

        JwtBuilder builder = Jwts.builder()
                .id(UUID.randomUUID().toString())          // jti：唯一标识，用于黑名单
                .subject(agentId)                          // sub：Agent ID
                .issuer(securityProperties.getJwt().getIssuer())  // iss：签发者
                .issuedAt(now)                             // iat：签发时间
                .expiration(expiry)                        // exp：过期时间
                .claim("agentType", agentType)             // 自定义：Agent 类型
                .claim("type", "access");                  // 自定义：Token 类型

        // 多租户支持：仅在 tenantId 非空时写入
        if (tenantId != null && !tenantId.isBlank()) {
            builder.claim("tenantId", tenantId);
        }

        return builder.signWith(getSigningKey()).compact();
    }

    /**
     * 生成会话 Token（短期，有效期 1 小时）
     * 适用于临时操作授权，如文件上传、一次性任务等
     *
     * @param agentId Agent 唯一标识
     * @return JWT Token 字符串
     */
    public String generateSessionToken(String agentId) {
        Date now = new Date();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(agentId)
                .issuer(securityProperties.getJwt().getIssuer())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 3600_000L))  // 1 小时
                .claim("type", "session")
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 生成刷新 Token（长期，有效期 7 天）
     * 用于在访问 Token 过期后换取新的访问 Token，不可直接用于 API 调用
     *
     * @param agentId Agent 唯一标识
     * @return JWT Refresh Token 字符串
     */
    public String generateRefreshToken(String agentId) {
        Date now = new Date();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(agentId)
                .issuer(securityProperties.getJwt().getIssuer())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 7 * 24 * 3600_000L))  // 7 天
                .claim("type", "refresh")
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 验证 Token 有效性（含黑名单检查）
     *
     * @param token JWT Token 字符串
     * @return true 表示 Token 有效，false 表示无效/过期/已撤销
     */
    public boolean validateToken(String token) {
        try {
            Claims claims = parseClaims(token);

            // 黑名单检查（需在配置中开启 enable-revocation-check）
            if (securityProperties.getJwt().isEnableRevocationCheck()) {
                String jti = claims.getId();
                if (isTokenRevoked(jti)) {
                    log.warn("Token 已被撤销: jti={}", jti);
                    return false;
                }
            }

            return true;
        } catch (ExpiredJwtException e) {
            log.warn("Token 已过期: subject={}", e.getClaims().getSubject());
        } catch (JwtException e) {
            log.warn("Token 无效: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Token 验证异常: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 从 Token 中获取 agentId（即 subject）
     *
     * @param token JWT Token 字符串
     * @return agentId
     */
    public String getAgentId(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * 从 Token 中获取 agentType
     *
     * @param token JWT Token 字符串
     * @return agentType，若不存在则返回 null
     */
    public String getAgentType(String token) {
        return parseClaims(token).get("agentType", String.class);
    }

    /**
     * 从 Token 中获取 tenantId
     *
     * @param token JWT Token 字符串
     * @return tenantId，若不存在则返回 null
     */
    public String getTenantId(String token) {
        return parseClaims(token).get("tenantId", String.class);
    }

    /**
     * 撤销 Token（加入黑名单）
     * <p>
     * 优先使用 Redis 存储（TTL 与 Token 剩余有效期一致），
     * 若 Redis 不可用则降级为内存列表（重启后失效）
     *
     * @param token 要撤销的 JWT Token 字符串
     */
    public void revokeToken(String token) {
        try {
            Claims claims = parseClaims(token);
            String jti = claims.getId();
            long ttlMillis = claims.getExpiration().getTime() - System.currentTimeMillis();

            if (ttlMillis <= 0) {
                // Token 已过期，无需加入黑名单
                log.debug("Token 已过期，无需撤销: jti={}", jti);
                return;
            }

            // 优先用 Redis 存储黑名单（TTL 自动过期，无需手动清理）
            redisTemplate.ifPresentOrElse(
                    rt -> {
                        rt.opsForValue().set(REVOKED_TOKEN_PREFIX + jti, "1", Duration.ofMillis(ttlMillis));
                        log.info("Token 已撤销（Redis）: jti={}, ttl={}ms", jti, ttlMillis);
                    },
                    () -> {
                        // 降级：内存黑名单（重启后失效，仅适用于开发/测试环境）
                        securityProperties.getJwt().getRevokedTokenIds().add(jti);
                        log.info("Token 已撤销（内存）: jti={}", jti);
                    }
            );
        } catch (Exception e) {
            log.warn("撤销 Token 失败: {}", e.getMessage());
        }
    }

    /**
     * 检查 Token jti 是否在黑名单中
     *
     * @param jti Token 唯一标识
     * @return true 表示已撤销
     */
    private boolean isTokenRevoked(String jti) {
        return redisTemplate
                .map(rt -> Boolean.TRUE.equals(rt.hasKey(REVOKED_TOKEN_PREFIX + jti)))
                .orElse(securityProperties.getJwt().getRevokedTokenIds().contains(jti));
    }

    /**
     * 解析 Token Claims（不验证过期时间以外的内容）
     * 若 Token 签名无效或格式错误，会抛出 JwtException
     *
     * @param token JWT Token 字符串
     * @return Claims 对象
     */
    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
