package com.fox.a2a.demo;

import com.fox.a2a.core.A2AAgent;
import com.fox.a2a.core.A2AConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Agent A 示例 —— 数据处理 Agent
 *
 * <p>演示功能：
 * <ol>
 *   <li>创建并启动 Agent（agentId="agent-a", agentType="data-processor", port=8001）</li>
 *   <li>注册消息处理器：对 payload 中的 "numbers" 数组求和，返回 {sum, count}</li>
 *   <li>注册任务处理器：处理 "sort-task"，对 "data" 数组排序后返回</li>
 *   <li>订阅 "data.events" topic，收到消息时打印日志</li>
 * </ol>
 */
@Slf4j
public class AgentADemo {

    public static void main(String[] args) throws Exception {
        // ── 1. 构建配置 ──────────────────────────────────────────────────────────
        A2AConfig config = A2AConfig.builder()
                .registryHost("localhost")
                .registryPort(9090)
                .agentId("agent-a")
                .agentName("数据处理Agent")
                .agentType("data-processor")
                .agentHost("localhost")
                .agentPort(8001)
                .capabilities(List.of("sum", "sort", "statistics"))
                .jwtToken(System.getenv("A2A_JWT_TOKEN"))
                .autoRegister(true)
                .build();

        A2AAgent agent = A2AAgent.create(config);

        // ── 2. 注册请求处理器（同步请求/响应） ────────────────────────────────────
        agent.onMessage((fromAgentId, messageId, payload) -> {
            log.info("收到来自 [{}] 的请求, messageId={}", fromAgentId, messageId);

            @SuppressWarnings("unchecked")
            List<Number> numbers = (List<Number>) payload.get("numbers");
            if (numbers == null || numbers.isEmpty()) {
                return Map.of("sum", 0.0, "count", 0);
            }

            double sum = numbers.stream().mapToDouble(Number::doubleValue).sum();
            log.info("求和完成: numbers={}, sum={}", numbers, sum);
            return Map.of("sum", sum, "count", numbers.size());
        });

        // ── 3. 注册任务处理器（异步任务委托） ─────────────────────────────────────
        agent.onTask((taskId, taskType, delegatorAgentId, input) -> {
            log.info("收到任务: taskId={}, taskType={}, from={}", taskId, taskType, delegatorAgentId);

            if ("sort-task".equals(taskType)) {
                @SuppressWarnings("unchecked")
                List<Integer> data = new ArrayList<>((List<Integer>) input.get("data"));
                Collections.sort(data);
                log.info("排序完成: taskId={}, sorted={}", taskId, data);
                return Map.of("sorted", data, "taskId", taskId);
            }

            throw new IllegalArgumentException("Unknown task type: " + taskType);
        });

        // ── 4. 订阅 topic ─────────────────────────────────────────────────────────
        agent.subscribe("data.events", (topic, publisherAgentId, payload) ->
                log.info("[PubSub] topic={}, from={}, payload={}", topic, publisherAgentId, payload));

        // ── 5. 启动 Agent ─────────────────────────────────────────────────────────
        agent.start();
        log.info("Agent A started, waiting for messages...");

        // 保持主线程运行，直到进程被终止
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Agent A shutting down...");
            agent.close();
        }));
        Thread.currentThread().join();
    }
}
