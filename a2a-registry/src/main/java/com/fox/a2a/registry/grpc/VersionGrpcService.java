package com.fox.a2a.registry.grpc;

import com.fox.a2a.proto.GetVersionRequest;
import com.fox.a2a.proto.GetVersionResponse;
import com.fox.a2a.proto.NegotiateVersionRequest;
import com.fox.a2a.proto.NegotiateVersionResponse;
import com.fox.a2a.proto.VersionServiceGrpc;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.List;

/**
 * 版本协商 gRPC 服务实现
 * 客户端连接时可先调用此服务确认版本兼容性，避免因版本不匹配导致运行时错误。
 *
 * 版本协商流程：
 * 1. 客户端启动时调用 NegotiateVersion，发送自身支持的协议版本列表
 * 2. 服务端从中选择双方都支持的最高版本
 * 3. 若无交集，返回 compatible=false，客户端应拒绝连接并提示升级
 * 4. 若客户端版本过旧，返回 deprecation_notice 提示升级
 *
 * 当前支持的协议版本：v1
 * 框架版本：1.1.0
 */
@Slf4j
@GrpcService
public class VersionGrpcService extends VersionServiceGrpc.VersionServiceImplBase {

    /** 当前框架版本 */
    private static final String FRAMEWORK_VERSION = "1.1.0";

    /** 当前 proto 协议版本 */
    private static final String CURRENT_PROTO_VERSION = "v1";

    /** 服务端支持的所有协议版本（按优先级从高到低排列） */
    private static final List<String> SUPPORTED_PROTO_VERSIONS = List.of("v1");

    /** 最低兼容客户端版本 */
    private static final String MIN_CLIENT_VERSION = "1.0.0";

    /**
     * 获取服务端版本信息
     * 客户端可通过此接口了解服务端的框架版本、协议版本和能力集合
     *
     * @param request          空请求
     * @param responseObserver 响应观察者
     */
    @Override
    public void getVersion(GetVersionRequest request,
                           StreamObserver<GetVersionResponse> responseObserver) {
        GetVersionResponse response = GetVersionResponse.newBuilder()
            .setFrameworkVersion(FRAMEWORK_VERSION)
            .setProtoVersion(CURRENT_PROTO_VERSION)
            .addAllSupportedProtoVersions(SUPPORTED_PROTO_VERSIONS)
            .setMinClientVersion(MIN_CLIENT_VERSION)
            // 服务端能力标记
            .putCapabilities("pubsub", "true")
            .putCapabilities("task_delegation", "true")
            .putCapabilities("streaming", "true")
            .putCapabilities("tls", "true")
            .putCapabilities("persistence", "optional")
            .putCapabilities("circuit_breaker", "true")
            .build();

        log.debug("GetVersion 请求，返回框架版本: {}", FRAMEWORK_VERSION);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    /**
     * 协商协议版本
     * 客户端发送其支持的版本列表，服务端从中选择双方都支持的最高版本
     *
     * @param request          包含客户端版本和支持的协议版本列表
     * @param responseObserver 响应观察者
     */
    @Override
    public void negotiateVersion(NegotiateVersionRequest request,
                                  StreamObserver<NegotiateVersionResponse> responseObserver) {
        String clientVersion = request.getClientVersion();
        List<String> clientProtoVersions = request.getSupportedProtoVersionsList();

        log.info("版本协商请求: clientVersion={}, clientProtoVersions={}", clientVersion, clientProtoVersions);

        // 从客户端支持的版本中，找到服务端也支持的第一个版本（优先级最高）
        String selectedVersion = clientProtoVersions.stream()
            .filter(SUPPORTED_PROTO_VERSIONS::contains)
            .findFirst()
            .orElse(null);

        boolean compatible = selectedVersion != null;
        String deprecationNotice = "";

        // 检查客户端版本是否过旧，给出废弃提示
        if (clientVersion != null && clientVersion.startsWith("1.0")) {
            deprecationNotice = "v1.0.x 将在 v2.0.0 中停止支持，请升级到 v1.1.x 或更高版本";
        }

        NegotiateVersionResponse response = NegotiateVersionResponse.newBuilder()
            .setCompatible(compatible)
            .setSelectedProtoVersion(selectedVersion != null ? selectedVersion : "")
            .setServerVersion(FRAMEWORK_VERSION)
            .setDeprecationNotice(deprecationNotice)
            // 协商后的能力集合（取双方交集）
            .putNegotiatedCapabilities("pubsub", "true")
            .putNegotiatedCapabilities("task_delegation", "true")
            .putNegotiatedCapabilities("streaming", "true")
            .build();

        if (!compatible) {
            log.warn("客户端版本不兼容: clientVersion={}, clientProtoVersions={}, serverSupports={}",
                clientVersion, clientProtoVersions, SUPPORTED_PROTO_VERSIONS);
        } else {
            log.info("版本协商成功: clientVersion={}, selectedProtoVersion={}", clientVersion, selectedVersion);
        }

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
