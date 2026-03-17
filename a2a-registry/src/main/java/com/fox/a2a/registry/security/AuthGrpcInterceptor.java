package com.fox.a2a.registry.security;

import com.fox.a2a.registry.metrics.A2AMetrics;
import io.grpc.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;

import java.util.Set;

/**
 * gRPC 全局认证拦截器
 * <p>
 * 对所有 gRPC 请求验证 JWT Token，未通过认证的请求返回 UNAUTHENTICATED 状态。
 * Token 从 Metadata 的 "authorization" 字段读取，格式：Bearer &lt;token&gt;
 * <p>
 * 使用 @GrpcGlobalServerInterceptor 自动注册为全局拦截器。
 * 注意：此拦截器与 MetricsGrpcInterceptor 共存，执行顺序由 Spring 管理。
 */
@Slf4j
@GrpcGlobalServerInterceptor
@RequiredArgsConstructor
public class AuthGrpcInterceptor implements ServerInterceptor {

    private final JwtTokenProvider jwtTokenProvider;
    private final A2AMetrics metrics;

    /**
     * 不需要 Authorization header 认证的方法白名单
     * Register 方法通过请求体中的 jwt_token 字段进行认证
     */
    private static final Set<String> PUBLIC_METHODS = Set.of(
            "Register"
    );

    /** Metadata key：Authorization header（gRPC 使用小写） */
    private static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    /** Context key：用于向下游传递已认证的 agentId */
    public static final Context.Key<String> AGENT_ID_CONTEXT_KEY = Context.key("agentId");

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String methodName = call.getMethodDescriptor().getBareMethodName();

        // 白名单方法直接放行，不做 Token 验证
        if (PUBLIC_METHODS.contains(methodName)) {
            log.debug("gRPC 公开方法，跳过认证: {}", methodName);
            return next.startCall(call, headers);
        }

        // 读取 Authorization header
        String authHeader = headers.get(AUTHORIZATION_KEY);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            metrics.recordAuthFail();
            log.warn("gRPC 认证失败，缺少 Authorization header，方法: {}", methodName);
            call.close(
                    Status.UNAUTHENTICATED.withDescription("缺少 Authorization header，格式：Bearer <token>"),
                    new Metadata()
            );
            return new ServerCall.Listener<>() {};
        }

        // 提取 Token（去掉 "Bearer " 前缀）
        String token = authHeader.substring(7);

        // 验证 Token（含签名、过期时间、黑名单检查）
        if (!jwtTokenProvider.validateToken(token)) {
            metrics.recordAuthFail();
            log.warn("gRPC 认证失败，Token 无效或已过期，方法: {}", methodName);
            call.close(
                    Status.UNAUTHENTICATED.withDescription("Token 无效或已过期"),
                    new Metadata()
            );
            return new ServerCall.Listener<>() {};
        }

        // 认证成功，记录指标
        metrics.recordAuthSuccess();

        // 将 agentId 注入 gRPC Context，供后续 Service 实现使用
        // 使用方式：AuthGrpcInterceptor.AGENT_ID_CONTEXT_KEY.get()
        String agentId = jwtTokenProvider.getAgentId(token);
        log.debug("gRPC 认证成功: agentId={}, method={}", agentId, methodName);

        Context ctx = Context.current().withValue(AGENT_ID_CONTEXT_KEY, agentId);
        return Contexts.interceptCall(ctx, call, headers, next);
    }
}
