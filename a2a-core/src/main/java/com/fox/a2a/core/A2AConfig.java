package com.fox.a2a.core;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * A2A 框架核心配置类
 * 包含 Registry 连接信息、本 Agent 信息、JWT 认证及连接参数
 */
@Data
@Builder
public class A2AConfig {

    // ==================== Registry 地址 ====================

    /** Registry 服务主机地址，默认 localhost */
    @Builder.Default
    private String registryHost = "localhost";

    /** Registry 服务端口，默认 9090 */
    @Builder.Default
    private int registryPort = 9090;

    // ==================== 本 Agent 信息 ====================

    /** Agent 唯一标识 */
    private String agentId;

    /** Agent 名称 */
    private String agentName;

    /** Agent 类型（如 "worker"、"coordinator" 等） */
    private String agentType;

    /** 本 Agent 对外暴露的 gRPC 主机地址 */
    private String agentHost;

    /** 本 Agent 对外暴露的 gRPC 端口 */
    private int agentPort;

    /** Agent 能力列表 */
    private List<String> capabilities;

    /** Agent 元数据（扩展信息） */
    private Map<String, String> metadata;

    // ==================== JWT 认证 ====================

    /** JWT token，用于向 Registry 认证 */
    private String jwtToken;

    // ==================== 连接配置 ====================

    /** 连接超时时间（毫秒），默认 5000 */
    @Builder.Default
    private int connectTimeoutMs = 5000;

    /** 请求超时时间（毫秒），默认 30000 */
    @Builder.Default
    private int requestTimeoutMs = 30000;

    /** 心跳间隔时间（毫秒），默认 10000 */
    @Builder.Default
    private int heartbeatIntervalMs = 10000;

    // ==================== 行为配置 ====================

    /** 是否在启动时自动注册到 Registry，默认 true */
    @Builder.Default
    private boolean autoRegister = true;
}
