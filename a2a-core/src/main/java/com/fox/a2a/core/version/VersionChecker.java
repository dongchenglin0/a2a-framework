package com.fox.a2a.core.version;

import com.fox.a2a.proto.GetVersionRequest;
import com.fox.a2a.proto.GetVersionResponse;
import com.fox.a2a.proto.NegotiateVersionRequest;
import com.fox.a2a.proto.NegotiateVersionResponse;
import com.fox.a2a.proto.VersionServiceGrpc;
import io.grpc.ManagedChannel;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 客户端版本检查工具
 * Agent 启动时调用，确认与 Registry 的版本兼容性。
 *
 * 设计原则：
 * - 版本检查失败不阻断 Agent 启动（向后兼容旧版 Registry）
 * - 版本不兼容时抛出 IllegalStateException，由调用方决定是否中止
 * - 版本即将废弃时打印 WARN 日志，不影响正常运行
 *
 * 使用示例：
 * <pre>
 * // 在 A2AClient.start() 中调用
 * VersionChecker checker = new VersionChecker(channel);
 * checker.checkCompatibility(); // 不兼容时抛出异常
 *
 * // 获取服务端详细版本信息
 * GetVersionResponse version = checker.getServerVersion();
 * log.info("服务端版本: {}", version.getFrameworkVersion());
 * </pre>
 */
@Slf4j
public class VersionChecker {

    /** 当前客户端框架版本 */
    private static final String CLIENT_VERSION = "1.1.0";

    /** 客户端支持的协议版本列表（按优先级从高到低） */
    private static final List<String> SUPPORTED_PROTO_VERSIONS = List.of("v1");

    /** gRPC 阻塞存根，用于同步调用版本协商接口 */
    private final VersionServiceGrpc.VersionServiceBlockingStub stub;

    /**
     * 构造版本检查器
     *
     * @param channel 已建立的 gRPC ManagedChannel（指向 Registry 服务）
     */
    public VersionChecker(ManagedChannel channel) {
        this.stub = VersionServiceGrpc.newBlockingStub(channel);
    }

    /**
     * 检查与 Registry 的版本兼容性
     * 不兼容时抛出 IllegalStateException；版本即将废弃时打印警告日志。
     * 若 Registry 不支持版本协商接口（旧版），则忽略错误继续启动。
     *
     * @throws IllegalStateException 当协议版本完全不兼容时
     */
    public void checkCompatibility() {
        try {
            NegotiateVersionResponse response = stub.negotiateVersion(
                NegotiateVersionRequest.newBuilder()
                    .setClientVersion(CLIENT_VERSION)
                    .addAllSupportedProtoVersions(SUPPORTED_PROTO_VERSIONS)
                    // 声明客户端能力
                    .putClientCapabilities("pubsub", "true")
                    .putClientCapabilities("task_delegation", "true")
                    .putClientCapabilities("streaming", "true")
                    .build()
            );

            if (!response.getCompatible()) {
                throw new IllegalStateException(
                    "A2A Framework 版本不兼容！" +
                    "客户端版本: " + CLIENT_VERSION +
                    "，客户端支持的协议版本: " + SUPPORTED_PROTO_VERSIONS +
                    "，服务端版本: " + response.getServerVersion() +
                    "。请升级客户端或服务端以解决版本冲突。"
                );
            }

            // 打印版本废弃警告
            if (!response.getDeprecationNotice().isEmpty()) {
                log.warn("⚠️  版本废弃提示: {}", response.getDeprecationNotice());
            }

            log.info("✅ 版本协商成功: 协议版本={}, 服务端框架版本={}",
                response.getSelectedProtoVersion(), response.getServerVersion());

        } catch (IllegalStateException e) {
            // 版本不兼容，向上抛出
            throw e;
        } catch (Exception e) {
            // 其他异常（如 Registry 不支持版本协商接口）：忽略，向后兼容旧版 Registry
            log.warn("版本检查失败（可能是旧版 Registry，忽略继续启动）: {}", e.getMessage());
        }
    }

    /**
     * 获取服务端版本信息（包含框架版本、协议版本、能力列表等）
     *
     * @return 服务端版本响应
     * @throws io.grpc.StatusRuntimeException 当 gRPC 调用失败时
     */
    public GetVersionResponse getServerVersion() {
        return stub.getVersion(GetVersionRequest.newBuilder().build());
    }

    /**
     * 获取当前客户端版本
     *
     * @return 客户端框架版本字符串
     */
    public static String getClientVersion() {
        return CLIENT_VERSION;
    }
}
