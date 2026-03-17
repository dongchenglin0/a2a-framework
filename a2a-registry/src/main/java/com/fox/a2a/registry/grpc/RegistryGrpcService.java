package com.fox.a2a.registry.grpc;

import com.fox.a2a.proto.*;
import com.fox.a2a.registry.config.RegistryProperties;
import com.fox.a2a.registry.security.JwtTokenProvider;
import com.fox.a2a.registry.store.AgentStore;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Registry gRPC 服务实现
 * 提供 Agent 注册、注销、心跳、发现、查询、监听等核心功能
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class RegistryGrpcService extends RegistryServiceGrpc.RegistryServiceImplBase {

    private final AgentStore agentStore;
    private final JwtTokenProvider jwtTokenProvider;
    private final RegistryProperties registryProperties;

    /**
     * 监听 Agent 变更的观察者列表
     * 使用 CopyOnWriteArrayList 保证并发安全
     */
    private final CopyOnWriteArrayList<StreamObserver<AgentEvent>> watchObservers =
            new CopyOnWriteArrayList<>();

    /**
     * Agent 注册
     * 验证 jwt_token，保存 AgentInfo（设置 registered_at），生成 sessionToken 返回
     */
    @Override
    public void register(RegisterRequest request, StreamObserver<RegisterResponse> responseObserver) {
        try {
            String jwtToken = request.getJwtToken();

            // 验证注册 Token
            if (!jwtTokenProvider.validateToken(jwtToken)) {
                responseObserver.onError(
                        Status.UNAUTHENTICATED
                                .withDescription("无效的 JWT Token，注册失败")
                                .asRuntimeException()
                );
                return;
            }

            AgentInfo agentInfo = request.getAgentInfo();
            String agentId = agentInfo.getAgentId();

            // 设置注册时间戳
            Timestamp registeredAt = Timestamp.newBuilder()
                    .setSeconds(Instant.now().getEpochSecond())
                    .build();

            // 构建完整的 AgentInfo（含注册时间和初始状态）
            AgentInfo fullAgentInfo = AgentInfo.newBuilder()
                    .mergeFrom(agentInfo)
                    .setRegisteredAt(registeredAt)
                    .setStatus(AgentStatus.ONLINE)
                    .build();

            // 保存到存储
            agentStore.save(fullAgentInfo);
            // 初始化心跳
            agentStore.updateHeartbeat(agentId, System.currentTimeMillis());

            // 生成会话 Token（1小时有效）
            String sessionToken = jwtTokenProvider.generateSessionToken(agentId);

            log.info("Agent 注册成功: {}, 类型: {}", agentId, agentInfo.getAgentType());

            // 广播注册事件给所有观察者
            broadcastAgentEvent(AgentEventType.AGENT_REGISTERED, fullAgentInfo);

            RegisterResponse response = RegisterResponse.newBuilder()
                    .setSuccess(true)
                    .setSessionToken(sessionToken)
                    .setMessage("注册成功")
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Agent 注册异常: {}", e.getMessage(), e);
            responseObserver.onError(
                    Status.INTERNAL
                            .withDescription(e.getMessage())
                            .asRuntimeException()
            );
        }
    }

    /**
     * Agent 注销
     * 验证 sessionToken，移除 Agent，通知所有 watchAgents 观察者
     */
    @Override
    public void deregister(DeregisterRequest request, StreamObserver<DeregisterResponse> responseObserver) {
        try {
            String sessionToken = request.getSessionToken();

            // 验证会话 Token
            if (!jwtTokenProvider.validateToken(sessionToken)) {
                responseObserver.onError(
                        Status.UNAUTHENTICATED
                                .withDescription("无效的 Session Token，注销失败")
                                .asRuntimeException()
                );
                return;
            }

            String agentId = jwtTokenProvider.getAgentId(sessionToken);

            // 查询 Agent 信息（用于广播事件）
            Optional<AgentInfo> agentInfoOpt = agentStore.findById(agentId);

            if (!agentStore.exists(agentId)) {
                responseObserver.onError(
                        Status.NOT_FOUND
                                .withDescription("Agent 不存在: " + agentId)
                                .asRuntimeException()
                );
                return;
            }

            // 移除 Agent
            agentStore.remove(agentId);
            log.info("Agent 注销成功: {}", agentId);

            // 广播注销事件给所有观察者
            agentInfoOpt.ifPresent(agentInfo ->
                    broadcastAgentEvent(AgentEventType.AGENT_DEREGISTERED, agentInfo)
            );

            DeregisterResponse response = DeregisterResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("注销成功")
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Agent 注销异常: {}", e.getMessage(), e);
            responseObserver.onError(
                    Status.INTERNAL
                            .withDescription(e.getMessage())
                            .asRuntimeException()
            );
        }
    }

    /**
     * Agent 心跳
     * 更新心跳时间戳和状态
     */
    @Override
    public void heartbeat(HeartbeatRequest request, StreamObserver<HeartbeatResponse> responseObserver) {
        try {
            String agentId = request.getAgentId();
            String sessionToken = request.getSessionToken();

            // 验证会话 Token
            if (!jwtTokenProvider.validateToken(sessionToken)) {
                responseObserver.onError(
                        Status.UNAUTHENTICATED
                                .withDescription("无效的 Session Token")
                                .asRuntimeException()
                );
                return;
            }

            if (!agentStore.exists(agentId)) {
                responseObserver.onError(
                        Status.NOT_FOUND
                                .withDescription("Agent 不存在: " + agentId)
                                .asRuntimeException()
                );
                return;
            }

            // 更新心跳时间戳
            agentStore.updateHeartbeat(agentId, System.currentTimeMillis());

            // 若 Agent 当前为 OFFLINE，则恢复为 ONLINE
            Optional<AgentInfo> agentInfoOpt = agentStore.findById(agentId);
            agentInfoOpt.ifPresent(agentInfo -> {
                if (agentInfo.getStatus() == AgentStatus.OFFLINE) {
                    agentStore.updateStatus(agentId, AgentStatus.ONLINE);
                    log.info("Agent [{}] 心跳恢复，状态更新为 ONLINE", agentId);
                }
            });

            // 若请求中携带了新状态，则更新
            if (request.hasStatus()) {
                agentStore.updateStatus(agentId, request.getStatus());
            }

            log.debug("Agent [{}] 心跳更新", agentId);

            HeartbeatResponse response = HeartbeatResponse.newBuilder()
                    .setSuccess(true)
                    .setServerTimestamp(Timestamp.newBuilder()
                            .setSeconds(Instant.now().getEpochSecond())
                            .build())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Agent 心跳处理异常: {}", e.getMessage(), e);
            responseObserver.onError(
                    Status.INTERNAL
                            .withDescription(e.getMessage())
                            .asRuntimeException()
            );
        }
    }

    /**
     * 发现 Agent
     * 按 type/capabilities 过滤，支持 limit 限制返回数量
     */
    @Override
    public void discover(DiscoverRequest request, StreamObserver<DiscoverResponse> responseObserver) {
        try {
            List<AgentInfo> agents;

            // 按类型过滤
            String agentType = request.getAgentType();
            if (agentType != null && !agentType.isEmpty()) {
                agents = agentStore.findByType(agentType);
            } else {
                agents = agentStore.findAll();
            }

            // 按能力过滤
            List<String> capabilities = request.getCapabilitiesList();
            if (capabilities != null && !capabilities.isEmpty()) {
                agents = agents.stream()
                        .filter(agent -> agent.getCapabilitiesList().stream()
                                .anyMatch(capabilities::contains))
                        .collect(Collectors.toList());
            }

            // 只返回 ONLINE 状态的 Agent
            agents = agents.stream()
                    .filter(agent -> agent.getStatus() == AgentStatus.ONLINE)
                    .collect(Collectors.toList());

            // 应用 limit 限制
            int limit = request.getLimit();
            if (limit > 0 && agents.size() > limit) {
                agents = agents.subList(0, limit);
            }

            log.debug("发现 Agent，类型: {}, 能力: {}, 结果数: {}", agentType, capabilities, agents.size());

            DiscoverResponse response = DiscoverResponse.newBuilder()
                    .addAllAgents(agents)
                    .setTotal(agents.size())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("发现 Agent 异常: {}", e.getMessage(), e);
            responseObserver.onError(
                    Status.INTERNAL
                            .withDescription(e.getMessage())
                            .asRuntimeException()
            );
        }
    }

    /**
     * 获取单个 Agent 信息
     */
    @Override
    public void getAgent(GetAgentRequest request, StreamObserver<GetAgentResponse> responseObserver) {
        try {
            String agentId = request.getAgentId();
            Optional<AgentInfo> agentInfoOpt = agentStore.findById(agentId);

            if (agentInfoOpt.isEmpty()) {
                responseObserver.onError(
                        Status.NOT_FOUND
                                .withDescription("Agent 不存在: " + agentId)
                                .asRuntimeException()
                );
                return;
            }

            GetAgentResponse response = GetAgentResponse.newBuilder()
                    .setAgentInfo(agentInfoOpt.get())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("获取 Agent 信息异常: {}", e.getMessage(), e);
            responseObserver.onError(
                    Status.INTERNAL
                            .withDescription(e.getMessage())
                            .asRuntimeException()
            );
        }
    }

    /**
     * 列出 Agent
     * 按 status 过滤，支持分页（page/page_size）
     */
    @Override
    public void listAgents(ListAgentsRequest request, StreamObserver<ListAgentsResponse> responseObserver) {
        try {
            List<AgentInfo> agents;

            // 按状态过滤
            if (request.hasStatus()) {
                agents = agentStore.findByStatus(request.getStatus());
            } else {
                agents = agentStore.findAll();
            }

            int total = agents.size();

            // 分页处理
            int pageSize = request.getPageSize() > 0 ? request.getPageSize() : 20;
            int page = request.getPage() > 0 ? request.getPage() : 1;
            int fromIndex = (page - 1) * pageSize;
            int toIndex = Math.min(fromIndex + pageSize, total);

            if (fromIndex < total) {
                agents = agents.subList(fromIndex, toIndex);
            } else {
                agents = List.of();
            }

            log.debug("列出 Agent，页码: {}, 每页: {}, 总数: {}", page, pageSize, total);

            ListAgentsResponse response = ListAgentsResponse.newBuilder()
                    .addAllAgents(agents)
                    .setTotal(total)
                    .setPage(page)
                    .setPageSize(pageSize)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("列出 Agent 异常: {}", e.getMessage(), e);
            responseObserver.onError(
                    Status.INTERNAL
                            .withDescription(e.getMessage())
                            .asRuntimeException()
            );
        }
    }

    /**
     * 监听 Agent 变更事件（服务端流式 RPC）
     * 将 StreamObserver 加入观察者列表，Agent 注册/注销时推送 AgentEvent
     */
    @Override
    public void watchAgents(WatchAgentsRequest request, StreamObserver<AgentEvent> responseObserver) {
        log.info("新增 watchAgents 观察者，当前观察者数: {}", watchObservers.size() + 1);

        // 加入观察者列表
        watchObservers.add(responseObserver);

        // 推送当前所有 ONLINE Agent 的初始状态
        try {
            List<AgentInfo> onlineAgents = agentStore.findByStatus(AgentStatus.ONLINE);
            for (AgentInfo agentInfo : onlineAgents) {
                AgentEvent event = AgentEvent.newBuilder()
                        .setEventType(AgentEventType.AGENT_REGISTERED)
                        .setAgentInfo(agentInfo)
                        .setTimestamp(Timestamp.newBuilder()
                                .setSeconds(Instant.now().getEpochSecond())
                                .build())
                        .build();
                responseObserver.onNext(event);
            }
        } catch (Exception e) {
            log.warn("推送初始 Agent 状态失败: {}", e.getMessage());
        }

        // 注意：此流不会主动关闭，由客户端断开或服务端广播时处理
    }

    /**
     * 广播 Agent 事件给所有观察者
     * 若观察者已断开，则从列表中移除
     *
     * @param eventType Agent 事件类型
     * @param agentInfo 相关的 Agent 信息
     */
    private void broadcastAgentEvent(AgentEventType eventType, AgentInfo agentInfo) {
        if (watchObservers.isEmpty()) {
            return;
        }

        AgentEvent event = AgentEvent.newBuilder()
                .setEventType(eventType)
                .setAgentInfo(agentInfo)
                .setTimestamp(Timestamp.newBuilder()
                        .setSeconds(Instant.now().getEpochSecond())
                        .build())
                .build();

        // 遍历所有观察者，推送事件
        List<StreamObserver<AgentEvent>> deadObservers = new java.util.ArrayList<>();
        for (StreamObserver<AgentEvent> observer : watchObservers) {
            try {
                observer.onNext(event);
            } catch (Exception e) {
                // 观察者已断开，标记为待移除
                log.warn("推送 AgentEvent 失败，移除观察者: {}", e.getMessage());
                deadObservers.add(observer);
            }
        }

        // 移除已断开的观察者
        watchObservers.removeAll(deadObservers);

        log.debug("广播 AgentEvent [{}] 给 {} 个观察者，Agent: {}",
                eventType, watchObservers.size(), agentInfo.getAgentId());
    }
}
