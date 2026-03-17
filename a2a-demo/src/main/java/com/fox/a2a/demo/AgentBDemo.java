package com.fox.a2a.demo;

import com.fox.a2a.core.A2AAgent;
import com.fox.a2a.core.A2AConfig;
import com.fox.a2a.proto.AgentInfo;
import com.fox.a2a.proto.Message;
import com.google.protobuf.util.JsonFormat;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Agent B Demo - Coordinator Agent
 * Demonstrates: service discovery, sync request, task delegation, pubsub publish
 * Prerequisites: start AgentADemo first, then run this demo.
 */
@Slf4j
public class AgentBDemo {

    public static void main(String[] args) throws Exception {
        A2AConfig config = A2AConfig.builder()
                .registryHost("localhost")
                .registryPort(9090)
                .agentId("agent-b")
                .agentName("CoordinatorAgent")
                .agentType("coordinator")
                .agentHost("localhost")
                .agentPort(8002)
                .jwtToken(System.getenv("A2A_JWT_TOKEN"))
                .autoRegister(true)
                .build();

        A2AAgent agent = A2AAgent.create(config);
        agent.start();
        log.info("Agent B started, beginning A2A communication demo...");

        // 1. Service discovery
        List<AgentInfo> processors = agent.discover("data-processor");
        log.info("Found {} data-processor agents: {}",
                processors.size(),
                processors.stream().map(AgentInfo::getAgentId).toList());

        // 2. Sync request/response: send sum request to agent-a
        log.info(">>> Sending sum request to agent-a: numbers=[1,2,3,4,5]");
        Message response = agent.sendRequest(
                "agent-a",
                Map.of("numbers", List.of(1, 2, 3, 4, 5))
        );
        log.info("Sum response received: messageId={}, payload={}",
                response.getMessageId(),
                JsonFormat.printer().print(response.getPayload()));

        // 3. Task delegation: delegate sort task to agent-a
        log.info(">>> Delegating sort task to agent-a: data=[5,3,1,4,2]");
        String taskId = agent.delegateTask(
                "agent-a",
                "sort-task",
                Map.of("data", List.of(5, 3, 1, 4, 2))
        );
        log.info("Sort task delegated, taskId={}", taskId);

        Thread.sleep(1000);
        var task = agent.getClient().getTaskStatus(taskId);
        log.info("Task status: {}", task.getStatus());

        // 4. Publish event to topic
        log.info(">>> Publishing event to data.events topic");
        agent.publish("data.events", Map.of(
                "event", "processing-complete",
                "agentId", "agent-b",
                "timestamp", System.currentTimeMillis()
        ));
        log.info("Event published to data.events topic");

        agent.close();
        log.info("Agent B demo complete.");
    }
}
