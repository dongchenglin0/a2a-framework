package com.fox.a2a.registry.security;

import com.fox.a2a.registry.config.JwtProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT Token 提供者
 * 负责生成、验证 JWT Token，支持 Agent 注册 Token 和会话 Token
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final JwtProperties jwtProperties;

    /**
     * 获取签名密钥
     * 使用 HMAC-SHA256 算法，密钥来自配置
     */
    private SecretKey getSecretKey() {
        byte[] keyBytes = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 生成 Agent 注册 Token（24小时有效）
     *
     * @param agentId   Agent 唯一标识
     * @param agentType Agent 类型
     * @return JWT Token 字符串
     */
    public String generateToken(String agentId, String agentType) {
        Date now = new Date();
        // 24小时有效期
        Date expiry = new Date(now.getTime() + jwtProperties.getExpirationHours() * 3600L * 1000L);

        return Jwts.builder()
                .subject(agentId)
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiration(expiry)
                .claim("agentType", agentType)
                .claim("tokenType", "registration")
                .signWith(getSecretKey())
                .compact();
    }

    /**
     * 生成 Agent 会话 Token（1小时有效）
     * 用于 Agent 注册成功后的后续操作鉴权
     *
     * @param agentId Agent 唯一标识
     * @return JWT Session Token 字符串
     */
    public String generateSessionToken(String agentId) {
        Date now = new Date();
        // 1小时有效期
        Date expiry = new Date(now.getTime() + 3600L * 1000L);

        return Jwts.builder()
                .subject(agentId)
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiration(expiry)
                .claim("tokenType", "session")
                .signWith(getSecretKey())
                .compact();
    }

    /**
     * 验证 Token 是否有效
     *
     * @param token JWT Token 字符串
     * @return 有效返回 true，否则返回 false
     */
    public boolean validateToken(String token) {
        try {
            // 使用 jjwt 0.12.5 新版 API
            Jwts.parser()
                    .verifyWith(getSecretKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("JWT Token 已过期: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.warn("不支持的 JWT Token: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            log.warn("JWT Token 格式错误: {}", e.getMessage());
        } catch (SignatureException e) {
            log.warn("JWT Token 签名验证失败: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("JWT Token 参数非法: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 从 Token 中提取 agentId（即 subject）
     *
     * @param token JWT Token 字符串
     * @return agentId 字符串
     */
    public String getAgentId(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(getSecretKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }

    /**
     * 从 Token 中提取 agentType
     *
     * @param token JWT Token 字符串
     * @return agentType 字符串，不存在则返回 null
     */
    public String getAgentType(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(getSecretKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.get("agentType", String.class);
    }
}
