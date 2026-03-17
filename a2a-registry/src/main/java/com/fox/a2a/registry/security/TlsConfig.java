package com.fox.a2a.registry.security;

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

/**
 * gRPC TLS 配置
 * <p>
 * 启用方式：在 application.yml 中设置 a2a.security.tls.enabled=true
 * 或通过环境变量 A2A_TLS_ENABLED=true
 * <p>
 * 支持两种模式：
 * 1. 单向 TLS：仅服务端提供证书，客户端验证服务端身份
 * 2. 双向 mTLS：客户端也需提供证书，双向验证（设置 mutual-tls=true）
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "a2a.security.tls.enabled", havingValue = "true")
public class TlsConfig {

    /**
     * 构建服务端 TLS SslContext
     * <p>
     * 证书文件格式要求：
     * - cert-chain-file: PEM 格式的证书链文件（含中间证书）
     * - private-key-file: PEM 格式的私钥文件（PKCS#8 格式）
     * - trust-cert-collection-file: PEM 格式的 CA 证书（mTLS 时使用）
     */
    @Bean
    public SslContext grpcSslContext(SecurityProperties securityProperties) throws Exception {
        SecurityProperties.TlsProperties tls = securityProperties.getTls();

        log.info("启用 gRPC TLS，证书路径: {}", tls.getCertChainFile());

        // 构建服务端 SSL 上下文（指定证书链和私钥）
        SslContextBuilder builder = GrpcSslContexts.forServer(
                new File(tls.getCertChainFile()),
                new File(tls.getPrivateKeyFile())
        );

        // 双向 mTLS 配置：要求客户端也提供证书
        if (tls.isMutualTls() && tls.getTrustCertCollectionFile() != null) {
            builder.trustManager(new File(tls.getTrustCertCollectionFile()))
                   .clientAuth(ClientAuth.REQUIRE);
            log.info("已启用双向 mTLS，信任证书: {}", tls.getTrustCertCollectionFile());
        }

        return builder.build();
    }
}
