import { A2AAgent, A2AConfig } from '../src';

/**
 * Node.js Agent 示例
 *
 * 演示功能：
 * 1. 创建 Node.js Agent（agentId="node-agent-1", agentType="node-processor", port=8003）
 * 2. 注册请求处理器：处理文本分析请求，返回字数统计
 * 3. 订阅 "data.events" topic
 * 4. 向 Java agent-a 发送求和请求
 * 5. 委托排序任务给 agent-a
 *
 * 前置条件：请先启动 AgentADemo（Java），再运行本示例。
 */
async function main() {
  // ── 1. 构建配置 ──────────────────────────────────────────────────────────────
  const config: A2AConfig = {
    registryHost: 'localhost',
    registryPort: 9090,
    agentId: 'node-agent-1',
    agentName: 'Node处理Agent',
    agentType: 'node-processor',
    agentHost: 'localhost',
    agentPort: 8003,
    capabilities: ['text-analysis', 'word-count'],
    jwtToken: process.env.A2A_JWT_TOKEN || '',
  };

  const agent = A2AAgent.create(config);

  // ── 2. 注册请求处理器（文本分析） ────────────────────────────────────────────
  agent.onRequest(async (fromAgentId: string, messageId: string, payload: Record<string, unknown>) => {
    console.log(`[Request] from=${fromAgentId}, messageId=${messageId}`);

    const text = (payload.text as string) || '';
    const words = text.split(/\s+/).filter((w: string) => w.length > 0);

    const result = {
      wordCount: words.length,
      charCount: text.length,
      uniqueWords: new Set(words).size,
    };

    console.log(`[TextAnalysis] wordCount=${result.wordCount}, charCount=${result.charCount}`);
    return result;
  });

  // ── 3. 订阅 topic ─────────────────────────────────────────────────────────────
  agent.subscribe(
    ['data.events'],
    (topic: string, publisherAgentId: string, payload: Record<string, unknown>) => {
      console.log(`[PubSub] topic=${topic}, from=${publisherAgentId}`, payload);
    },
  );

  // ── 4. 启动 Agent ─────────────────────────────────────────────────────────────
  await agent.start();
  console.log('Node Agent started!');

  // ── 5. 向 Java Agent 发送同步请求（求和） ────────────────────────────────────
  try {
    console.log('>>> 向 Java agent-a 发送求和请求: numbers=[10,20,30,40,50]');
    const result = await agent.sendRequest('agent-a', {
      numbers: [10, 20, 30, 40, 50],
    });
    console.log('Java Agent 求和结果:', result);
  } catch (e) {
    console.error('请求失败:', e);
  }

  // ── 6. 委托排序任务给 Java Agent ─────────────────────────────────────────────
  try {
    console.log('>>> 委托排序任务到 agent-a: data=[9,3,7,1,5]');
    const taskId = await agent.delegateTask('agent-a', 'sort-task', {
      data: [9, 3, 7, 1, 5],
    });
    console.log('排序任务已委托, taskId:', taskId);
  } catch (e) {
    console.error('任务委托失败:', e);
  }

  // ── 7. 保持运行，等待外部请求 ─────────────────────────────────────────────────
  console.log('Node Agent 正在运行，按 Ctrl+C 退出...');
  process.on('SIGINT', () => {
    console.log('\nNode Agent shutting down...');
    agent.close();
    process.exit(0);
  });
}

main().catch(console.error);
