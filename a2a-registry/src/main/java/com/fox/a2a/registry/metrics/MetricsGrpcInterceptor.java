package com.fox.a2a.registry.metrics;

import io.grpc.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;

/**
 * gRPC 全局拦截器 - 自动记录所有 gRPC 方法的调用指标
 * 包括：调用次数、成功/失败次数、响应时间
 * <p>
 * 使用 @GrpcGlobalServerInterceptor 自动注册为全局拦截器，无需手动配置
 */
@Slf4j
@GrpcGlobalServerInterceptor
@RequiredArgsConstructor
public class MetricsGrpcInterceptor implements ServerInterceptor {

    private final MeterRegistry meterRegistry;

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        // 获取方法名和服务名，用于指标 tag
        String methodName = call.getMethodDescriptor().getBareMethodName();
        String serviceName = call.getMethodDescriptor().getServiceName();

        // 在调用开始时启动计时器
        Timer.Sample sample = Timer.start(meterRegistry);

        // 包装 ServerCall，在 close() 时记录指标（此时状态码已确定）
        ServerCall<ReqT, RespT> wrappedCall = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                String statusCode = status.getCode().name();

                // 记录调用次数（按方法名、服务名、状态码分组）
                Counter.builder("a2a_grpc_calls_total")
                        .tag("method", methodName != null ? methodName : "unknown")
                        .tag("service", serviceName != null ? serviceName : "unknown")
                        .tag("status", statusCode)
                        .description("gRPC 调用总次数")
                        .register(meterRegistry)
                        .increment();

                // 记录响应时间（按方法名、状态码分组，含百分位数）
                sample.stop(Timer.builder("a2a_grpc_call_duration_ms")
                        .tag("method", methodName != null ? methodName : "unknown")
                        .tag("status", statusCode)
                        .description("gRPC 调用响应时间")
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(meterRegistry));

                log.debug("gRPC 调用完成: service={}, method={}, status={}", serviceName, methodName, statusCode);

                super.close(status, trailers);
            }
        };

        return next.startCall(wrappedCall, headers);
    }
}
