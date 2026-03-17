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

/**
 * Registry gRPC service implementation (v1.1)
 * Depends on RegistryProvider instead of AgentStore directly
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class RegistryGrpcService extends RegistryServiceGrpc.RegistryServiceImplBase {

    private final RegistryProvider registryProvider;
    private final JwtTokenProvider jwtTokenProvider;
    private final RegistryProperties registryProperties;
    private final A2AMetrics metrics;

    private final CopyOnWriteArrayList<StreamObserver<AgentEvent>> watchObservers =
            new CopyOnWriteArrayList<>();

    @Override
    public void register(RegisterRequest request, StreamObserver<RegisterResponse> responseObserver) {
        try {
            if (!jwtTokenProvider.validateToken(request.getJwtToken())) {
                metrics.recordAuthFail();
                responseObserver.onError(Status.UNAUTHENTICATED
                        .withDescription("Invalid JWT token").asRuntimeException());
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

            registryProvider.register(fullAgentInfo);
            metrics.recordAgentRegister();

            String sessionToken = jwtTokenProvider.generateSessionToken(agentId);
            log.info("Agent registered: {}, type: {}, provider: {}",
                    agentId, agentInfo.getAgentType(), registryProvider.getName());

            broadcastAgentEvent(AgentEventType.AGENT_REGISTERED, fullAgentInfo);

            responseObserver.onNext(RegisterResponse.newBuilder()
                    .setSuccess(true)
                    .setSessionToken(sessionToken)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Register error: {}", e.getMessage(), e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void deregister(DeregisterRequest request, StreamObserver<DeregisterResponse> responseObserver) {
        try {
            if (!jwtTokenProvider.validateToken(request.getSessionToken())) {
                responseObserver.onError(Status.UNAUTHENTICATED
                        .withDescription("Invalid session token").asRuntimeException());
                return;
            }

            String agentId = jwtTokenProvider.getAgentId(request.getSessionToken());
            Optional<AgentInfo> agentInfoOpt = registryProvider.findById(agentId);

            if (!registryProvider.exists(agentId)) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Agent not found: " + agentId).asRuntimeException());
                return;
            }

            registryProvider.deregister(agentId);
            metrics.recordAgentDeregister();
            log.info("Agent deregistered: {}", agentId);

            agentInfoOpt.ifPresent(info -> broadcastAgentEvent(AgentEventType.AGENT_DEREGISTERED, info));

            responseObserver.onNext(DeregisterResponse.newBuilder().setSuccess(true).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Deregister error: {}", e.getMessage(), e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void heartbeat(HeartbeatRequest request, StreamObserver<HeartbeatResponse> responseObserver) {
        try {
            String agentId = request.getAgentId();
            if (!jwtTokenProvider.validateToken(request.getSessionToken())) {
                responseObserver.onError(Status.UNAUTHENTICATED
                        .withDescription("Invalid session token").asRuntimeException());
                return;
            }

            if (!registryProvider.exists(agentId)) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Agent not found: " + agentId).asRuntimeException());
                return;
            }

            AgentStatus newStatus = request.getStatus() != AgentStatus.UNKNOWN
                    ? request.getStatus() : AgentStatus.ONLINE;
            registryProvider.heartbeat(agentId, newStatus, System.currentTimeMillis());
            metrics.recordHeartbeat();
            log.debug("Heartbeat: {}", agentId);

            responseObserver.onNext(HeartbeatResponse.newBuilder()
                    .setSuccess(true)
                    .setServerTime(Timestamp.newBuilder()
                            .setSeconds(Instant.now().getEpochSecond()).build())
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Heartbeat error: {}", e.getMessage(), e);
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
            log.debug("Discover: type={}, results={}", request.getAgentType(), agents.size());

            responseObserver.onNext(DiscoverResponse.newBuilder()
                    .addAllAgents(agents)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Discover error: {}", e.getMessage(), e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void getAgent(GetAgentRequest request, StreamObserver<GetAgentResponse> responseObserver) {
        try {
            Optional<AgentInfo> agentInfoOpt = registryProvider.findById(request.getAgentId());
            if (agentInfoOpt.isEmpty()) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Agent not found: " + request.getAgentId()).asRuntimeException());
                return;
            }
            responseObserver.onNext(GetAgentResponse.newBuilder()
                    .setAgent(agentInfoOpt.get()).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void listAgents(ListAgentsRequest request, StreamObserver<ListAgentsResponse> responseObserver) {
        try {
            List<AgentInfo> agents;
            if (request.getStatusFilter() != AgentStatus.UNKNOWN) {
                agents = registryProvider.findByStatus(request.getStatusFilter());
            } else {
                agents = registryProvider.findAll();
            }

            int total = agents.size();
            int pageSize = request.getPageSize() > 0 ? request.getPageSize() : 20;
            int page = request.getPage() > 0 ? request.getPage() : 1;
            int from = (page - 1) * pageSize;
            int to = Math.min(from + pageSize, total);
            List<AgentInfo> paged = from < total ? agents.subList(from, to) : List.of();

            responseObserver.onNext(ListAgentsResponse.newBuilder()
                    .addAllAgents(paged)
                    .setTotal(total)
                    .setPage(page)
                    .setPageSize(pageSize)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void watchAgents(WatchRequest request, StreamObserver<AgentEvent> responseObserver) {
        log.info("New watchAgents observer, total: {}", watchObservers.size() + 1);
        watchObservers.add(responseObserver);

        try {
            registryProvider.findByStatus(AgentStatus.ONLINE).forEach(agentInfo ->
                responseObserver.onNext(AgentEvent.newBuilder()
                    .setEventType(AgentEventType.AGENT_REGISTERED)
                    .setAgent(agentInfo)
                    .setOccurredAt(Timestamp.newBuilder()
                            .setSeconds(Instant.now().getEpochSecond()).build())
                    .build())
            );
        } catch (Exception e) {
            log.warn("Failed to push initial snapshot: {}", e.getMessage());
        }
    }

    private void broadcastAgentEvent(AgentEventType eventType, AgentInfo agentInfo) {
        if (watchObservers.isEmpty()) return;

        AgentEvent event = AgentEvent.newBuilder()
                .setEventType(eventType)
                .setAgent(agentInfo)
                .setOccurredAt(Timestamp.newBuilder()
                        .setSeconds(Instant.now().getEpochSecond()).build())
                .build();

        List<StreamObserver<AgentEvent>> dead = new ArrayList<>();
        for (StreamObserver<AgentEvent> observer : watchObservers) {
            try {
                observer.onNext(event);
            } catch (Exception e) {
                log.warn("Failed to push AgentEvent, removing observer: {}", e.getMessage());
                dead.add(observer);
            }
        }
        watchObservers.removeAll(dead);
    }
}
