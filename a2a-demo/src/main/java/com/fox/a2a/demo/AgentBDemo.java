package com.fox.a2a.demo;

import com.fox.a2a.core.A2AAgent;
import com.fox.a2a.core.A2AConfig;
import com.fox.a2a.proto.AgentInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Agent B 示例 —— 协调 Agent
 *
 * <p>演示完整的 A2A 通讯流程：
 * <ol>
 *   <li>创建并启动 Agent B（agentId="agent-b", agentType="coordinator", port=8002）</li>
 *   <li>发现所有 "data-processor" 类型的 Agent</li>
 *   <li>向 agent-a 发送同步请求，计算 [1,2,3,4,5] 的和</li>
 *   <li>向 agent-a 委托排序任务，输入 [5,3,1,4,2]，轮询结果</li>
 *   <li>发布一条消息到 "data.events" topic</li>
 * </ol>
 *
 * <p><b>前置条件：</b>请先启动 AgentADemo，再运行本示例。
 */
@Slf4j
public class AgentBDemo {

    public static void main(String[] args) throws Exception {
        // ── 1. 构建配置并启动 ─────────────────────────────────────────────────────
        A2AConfig config = A2AConfig.builder()
                .registryHost("localhost")
                .registryPort(9090)
                .agentId("agent-b")
                .agentName("协调Agent")
                .agentType("coordinator")
                .agentHost("localhost")
                .agentPort(8002)
                .jwtToken(System.getenv("A2A_JWT_TOKEN"))
                .autoRegister(true)
                .build();

        A2AAgent agent = A2AAgent.create(config);
        agent.start();
        log.info("Agent B 已启动，开始演示 A2A 通讯流程...");

        // ── 2. 服务发现：查找所有 data-processor 类型的 Agent ─────────────────────
        List<AgentInfo> processors = agent.discover("data-processor");
        log.info("发现 {} 个数据处理Agent: {}",
                processors.size(),
                processors.stream().map(AgentInfo::getAgentId).toList());

        // ── 3. 同步请求/响应：向 agent-a 求和 ────────────────────────────────────
        log.info(">>> 发送求和请求到 agent-a: numbers=[1,2,3,4,5]");
        Map<String, Object> response = agent.sendRequest(
                "agent-a",
                Map.of("numbers", List.of(1, 2, 3, 4, 5))
        );
        log.info("求和结果: sum={}, count={}", response.get("sum"), response.get("count"));

        // ── 4. 任务委托：向 agent-a 委托排序任务 ─────────────────────────────────
        log.info(">>> 委托排序任务到 agent-a: data=[5,3,1,4,2]");
        String taskId = agent.delegateTask(
                "agent-a",
                "sort-task",
                Map.of("data", List.of(5, 3, 1, 4, 2))
        );
        log.info("排序任务已委托, taskId={}", taskId);

        // 等待任务执行完成后轮询状态
        Thread.sleep(1000);
        var task = agent.getClient().getTaskStatus(taskId);
        log.info("任务状态: {}, 结果: {}", task.getStatus(), task.getOutput());

        // ── 5. 发布/订阅：向 data.events topic 发布事件 ──────────────────────────
        log.info(">>> 发布事件到 data.events topic");
        agent.publish("data.events", Map.of(
                "event", "processing-complete",
                "agentId", "agent-b",
                "timestamp", System.currentTimeMillis()
        ));
        log.info("事件已发布到 data.events topic");

        // ── 6. 关闭 Agent ─────────────────────────────────────────────────────────
        agent.close();
        log.info("Agent B 演示完成");
    }
}
