package com.fox.a2a.demo;

import com.fox.a2a.core.A2AAgent;
import com.fox.a2a.core.A2AConfig;
import com.fox.a2a.core.handler.MessageHandler;
import com.fox.a2a.core.handler.TaskHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Agent A Demo - Data Processor Agent
 * Demonstrates: message handling, task handling, pubsub subscription
 */
@Slf4j
public class AgentADemo {

    public static void main(String[] args) throws Exception {
        A2AConfig config = A2AConfig.builder()
                .registryHost("localhost")
                .registryPort(9090)
                .agentId("agent-a")
                .agentName("DataProcessorAgent")
                .agentType("data-processor")
                .agentHost("localhost")
                .agentPort(8001)
                .capabilities(List.of("sum", "sort", "statistics"))
                .jwtToken(System.getenv("A2A_JWT_TOKEN"))
                .autoRegister(true)
                .build();

        A2AAgent agent = A2AAgent.create(config);

        // Register sync request handler
        agent.onMessage(new MessageHandler() {
            @Override
            public Map<String, Object> handleRequest(String fromAgentId, String messageId,
                                                      Map<String, Object> payload) {
                log.info("Received request from [{}], messageId={}", fromAgentId, messageId);
                @SuppressWarnings("unchecked")
                List<Number> numbers = (List<Number>) payload.get("numbers");
                if (numbers == null || numbers.isEmpty()) {
                    return Map.of("sum", 0.0, "count", 0);
                }
                double sum = numbers.stream().mapToDouble(Number::doubleValue).sum();
                log.info("Sum result: numbers={}, sum={}", numbers, sum);
                return Map.of("sum", sum, "count", numbers.size());
            }

            @Override
            public void handleMessage(String fromAgentId, String topic, Map<String, Object> payload) {
                log.info("Received one-way message from [{}], topic={}", fromAgentId, topic);
            }
        });

        // Register async task handler
        agent.onTask(new TaskHandler() {
            @Override
            public Map<String, Object> handleTask(String taskId, String taskType,
                                                   String delegatorAgentId,
                                                   Map<String, Object> input) {
                log.info("Received task: taskId={}, taskType={}, from={}", taskId, taskType, delegatorAgentId);
                if ("sort-task".equals(taskType)) {
                    @SuppressWarnings("unchecked")
                    List<Integer> data = new ArrayList<>((List<Integer>) input.get("data"));
                    Collections.sort(data);
                    log.info("Sort complete: taskId={}, sorted={}", taskId, data);
                    return Map.of("sorted", data, "taskId", taskId);
                }
                throw new IllegalArgumentException("Unknown task type: " + taskType);
            }
        });

        // Subscribe to topic
        agent.subscribe("data.events");

        // Start agent
        agent.start();
        log.info("Agent A started, waiting for messages...");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Agent A shutting down...");
            agent.close();
        }));
        Thread.currentThread().join();
    }
}
