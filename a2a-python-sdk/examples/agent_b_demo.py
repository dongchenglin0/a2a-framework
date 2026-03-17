"""
Agent B 示例 - 协调 Agent
演示：发现 Agent、发送请求、委托任务、发布事件
"""
import asyncio
import logging
import time
from a2a import A2AAgent, A2AConfig

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s"
)
logger = logging.getLogger(__name__)


async def main():
    config = A2AConfig(
        registry_host="localhost",
        registry_port=9090,
        agent_id="python-agent-b",
        agent_name="Python协调Agent",
        agent_type="coordinator",
        agent_host="localhost",
        agent_port=8012,
        jwt_token="your-jwt-token",
        auto_register=True,
    )

    agent = A2AAgent.create(config)
    await agent.start()

    try:
        # 1. 发现 data-processor 类型的 Agent
        processors = await agent.discover("data-processor")
        logger.info(f"发现 {len(processors)} 个数据处理Agent: {[a.agent_id for a in processors]}")

        if not processors:
            logger.warning("未发现任何数据处理Agent，请先启动 agent_a_demo.py")
            return

        # 2. 同步请求：求和
        logger.info("发送求和请求...")
        result = await agent.send_request(
            "python-agent-a",
            {"numbers": [1, 2, 3, 4, 5]}
        )
        logger.info(f"求和结果: sum={result.get('sum')}, count={result.get('count')}, avg={result.get('avg')}")

        # 3. 委托任务：排序
        logger.info("委托排序任务...")
        task_id = await agent.delegate_task(
            "python-agent-a",
            "sort-task",
            {"data": [5, 3, 1, 4, 2]}
        )
        logger.info(f"排序任务已委托, task_id={task_id}")

        # 等待任务完成
        await asyncio.sleep(1)
        task = await agent.client.get_task_status(task_id)
        logger.info(f"任务状态: {task.status}, 结果: {task.output}")

        # 4. 发布事件到 PubSub topic
        logger.info("发布事件到 data.events...")
        msg_id = await agent.publish("data.events", {
            "event": "processing-complete",
            "agent_id": "python-agent-b",
            "timestamp": int(time.time()),
            "results": {"sum": result.get("sum"), "task_id": task_id},
        })
        logger.info(f"事件已发布, message_id={msg_id}")

        # 5. 单向发送消息
        logger.info("单向发送通知消息...")
        await agent.send(
            "python-agent-a",
            "notifications",
            {"type": "info", "message": "协调任务完成"}
        )
        logger.info("通知已发送")

    finally:
        await agent.close()
        logger.info("Python Agent B 演示完成")


if __name__ == "__main__":
    asyncio.run(main())
