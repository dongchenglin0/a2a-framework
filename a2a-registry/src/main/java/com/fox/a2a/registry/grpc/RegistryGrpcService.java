package com.fox.a2a.registry.grpc;

import com.fox.a2a.proto.*;
import com.fox.a2a.registry.config.RegistryProperties;
import com.fox.a2a.registry.metrics.A2AMetrics;
import com.fox.a2a.registry.provider.RegistryProvider;
import com.fox.a2a.registry.security.JwtTokenProvider;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Registry gRPC 服务实现（v1.1 重构版）
 * 依赖从 AgentStore 升级为 RegistryProvider，支持多种注册中心后端
 * 新增：Prometheus 指标记录
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class RegistryGrpcService extends RegistryServiceGrpc.RegistryServiceImplBase {

    private final RegistryProvider registryProvider;
    private final JwtTokenProvider jwtTokenProvider;
    private final RegistryProperties registryProperties;
    private final A2AMetrics metrics;

    /** 监听 Agent 变更的观察者列表（线程安全） */
    private final CopyOnWriteArrayList<StreamObserver<AgentEvent>> watchObservers =
            new CopyOnWriteArrayList<>();

    @Override
    public void register(RegisterRequest request, StreamObserver<RegisterResponse> responseObserver) {
        try {
            String jwtToken = request.getJwtToken();
            if (!jwtTokenProvider.validateToken(jwtToken)) {
                metrics.recordAuthFail();
                responseObserver.onError(Status.UNAUTHENTICATED
                        .withDescription("无效的 JWT Token，注册失败").asRuntimeException());
                return;
            }
            metrics.recordAuthSuccess();

            AgentInfo agentInfo = request.getAgentInfo();
            String agentId = agentInfo.getAgentId();

            Timestamp now = Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build();
            AgentInfo fullAgentInfo = AgentInfo.newBuilder()
                    .mergeFrom(agentInfo)
                    .setRegisteredAt(now)
                    .setLastHeartbeat(now)
                    .setStatus(AgentStatus.ONLINE)
                    .build();

            // 通过 RegistryProvider 注册（内部会触发 fireAgentRegistered 事件）
            registryProvider.register(fullAgentInfo);
            metrics.recordAgentRegister();

            String sessionToken = jwtTokenProvider.generateSessionToken(agentId);
            log.info("Agent 注册成功: {}, 类型: {}, 注册中心: {}",
                    agentId, agentInfo.getAgentType(), registryProvider.getName());

            broadcastAgentEvent(AgentEventType.AGENT_REGISTERED, fullAgentInfo);

            responseObserver.onNext(RegisterResponse.newBuilder()
                    .setSuccess(true)
                    .setSessionToken(sessionToken)
                    .build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Agent 注册异常: {}", e.getMessage(), e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void deregister(DeregisterRequest request, StreamObserver<DeregisterResponse> responseObserver) {
        try {
            String sessionToken = request.getSessionToken();
            if (!jwtTokenProvider.validateToken(sessionToken)) {
                responseObserver.onError(Status.UNAUTHENTICATED
                        .withDescription("无效的 Session Token").asRuntimeException());
                return;
            }

            String agentId = jwtTokenProvider.getAgentId(sessionToken);
            Optional<AgentInfo> agentInfoOpt = registryProvider.findById(agentId);

            if (!registryProvider.exists(agentId)) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Agent 不存在: " + agentId).asRuntimeException());
                return;
            }

            registryProvider.deregister(agentId);
            metrics.recordAgentDeregister();
            log.info("Agent 注销成功: {}", agentId);

            agentInfoOpt.ifPresent(info -> broadcastAgentEvent(AgentEventType.AGENT_DEREGISTERED, info));

            responseObserver.onNext(DeregisterResponse.newBuilder().setSuccess(true).build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Agent 注销异常: {}", e.getMessage(), e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void heartbeat(HeartbeatRequest request, StreamObserver<HeartbeatResponse> responseObserver) {
        try {
            String agentId = request.getAgentId();
            if (!jwtTokenProvider.validateToken(request.getSessionToken())) {
                responseObserver.onError(Status.UNAUTHENTICATED
                        .withDescription("无效的 Session Token").asRuntimeException());
                return;
            }

            if (!registryProvider.exists(agentId)) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Agent 不存在: " + agentId).asRuntimeException());
                return;
            }

            AgentStatus newStatus = request.hasStatus() ? request.getStatus() : AgentStatus.ONLINE;
            registryProvider.heartbeat(agentId, newStatus, System.currentTimeMillis());
            metrics.recordHeartbeat();
            log.debug("Agent [{}] 心跳更新", agentId);

            responseObserver.onNext(HeartbeatResponse.newBuilder()
                    .setSuccess(true)
                    .setServerTimestamp(Timestamp.newBuilder()
                            .setSeconds(Instant.now().getEpochSecond()).build())
                    .build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("心跳处理异常: {}", e.getMessage(), e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void discover(DiscoverRequest request, StreamObserver<DiscoverResponse> responseObserver) {
        try {
            io.micrometer.core.instrument.Timer.Sample sample = metrics.startTimer();

            List<AgentInfo> agents = registryProvider.discover(
                    request.getAgentType(),
                    request.getCapabilitiesList(),
                    request.getLimit()
            );

            metrics.recordDiscoverLatency(sample);
            log.debug("发现 Agent，类型: {}, 结果数: {}", request.getAgentType(), agents.size());

            responseObserver.onNext(DiscoverResponse.newBuilder()
                    .addAllAgents(agents)
                    .setTotal(agents.size())
                    .build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("发现 Agent 异常: {}", e.getMessage(), e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void getAgent(GetAgentRequest request, StreamObserver<GetAgentResponse> responseObserver) {
        try {
            Optional<AgentInfo> agentInfoOpt = registryProvider.findById(request.getAgentId());
            if (agentInfoOpt.isEmpty()) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Agent 不存在: " + request.getAgentId()).asRuntimeException());
                return;
            }
            responseObserver.onNext(GetAgentResponse.newBuilder().setAgentInfo(agentInfoOpt.get()).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void listAgents(ListAgentsRequest request, StreamObserver<ListAgentsResponse> responseObserver) {
        try {
            List<AgentInfo> agents = request.hasStatus()
                    ? registryProvider.findByStatus(request.getStatus())
                    : registryProvider.findAll();

            int total = agents.size();
            int pageSize = request.getPageSize() > 0 ? request.getPageSize() : 20;
            int page = request.getPage() > 0 ? request.getPage() : 1;
            int from = (page - 1) * pageSize;
            int to = Math.min(from + pageSize, total);
            List<AgentInfo> paged = from < total ? agents.subList(from, to) : List.of();

            responseObserver.onNext(ListAgentsResponse.newBuilder()
                    .addAllAgents(paged).setTotal(total).setPage(page).setPageSize(pageSize).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void watchAgents(WatchAgentsRequest request, StreamObserver<AgentEvent> responseObserver) {
        log.info("新增 watchAgents 观察者，当前数: {}", watchObservers.size() + 1);
        watchObservers.add(responseObserver);

        // 推送当前所有在线 Agent 的初始快照
        try {
            registryProvider.findByStatus(AgentStatus.ONLINE).forEach(agentInfo ->
                responseObserver.onNext(AgentEvent.newBuilder()
                    .setEventType(AgentEventType.AGENT_REGISTERED)
                    .setAgentInfo(agentInfo)
                    .setTimestamp(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build())
                    .build())
            );
        } catch (Exception e) {
            log.warn("推送初始快照失败: {}", e.getMessage());
        }
    }

    private void broadcastAgentEvent(AgentEventType eventType, AgentInfo agentInfo) {
        if (watchObservers.isEmpty()) return;

        AgentEvent event = AgentEvent.newBuilder()
                .setEventType(eventType)
                .setAgentInfo(agentInfo)
                .setTimestamp(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build())
                .build();

        List<StreamObserver<AgentEvent>> dead = new ArrayList<>();
        for (StreamObserver<AgentEvent> observer : watchObservers) {
            try {
                observer.onNext(event);
            } catch (Exception e) {
                log.warn("推送 AgentEvent 失败，移除观察者: {}", e.getMessage());
                dead.add(observer);
            }
        }
        watchObservers.removeAll(dead);
    }
}
