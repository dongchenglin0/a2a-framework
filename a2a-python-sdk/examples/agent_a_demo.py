"""
Agent A 示例 - 数据处理 Agent
演示：接收同步请求、处理任务、订阅 PubSub 事件
"""

import asyncio
import logging

from a2a import A2AAgent, A2AConfig

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
)
logger = logging.getLogger("agent-a")


async def main():
    # ---- 配置 ----
    config = A2AConfig(
        registry_host="localhost",
        registry_port=9090,
        agent_id="python-agent-a",
        agent_name="Python数据处理Agent",
        agent_type="data-processor",
        agent_host="localhost",
        agent_port=8011,
        capabilities=["sum", "sort", "statistics"],
        jwt_token="your-jwt-token",
    )

    agent = A2AAgent.create(config)

    # ---- 注册同步请求处理器（装饰器用法）----
    @agent.on_request
    async def handle_request(from_agent_id: str, message_id: str, payload: dict) -> dict:
        """
        处理来自其他 Agent 的同步请求。
        支持 sum / sort / statistics 操作。
        """
        logger.info(f"收到请求: from={from_agent_id}, message_id={message_id}, payload={payload}")

        numbers = payload.get("numbers", [])
        if not isinstance(numbers, list):
            return {"error": "numbers 字段必须是列表"}

        total = sum(numbers)
        count = len(numbers)
        avg = total / count if count > 0 else 0
        sorted_nums = sorted(numbers)

        result = {
            "sum": total,
            "count": count,
            "avg": avg,
            "min": sorted_nums[0] if sorted_nums else None,
            "max": sorted_nums[-1] if sorted_nums else None,
        }
        logger.info(f"请求处理完成: result={result}")
        return result

    # ---- 注册任务处理器（装饰器用法）----
    @agent.on_task
    async def handle_task(
        task_id: str,
        task_type: str,
        delegator_id: str,
        input_data: dict,
    ) -> dict:
        """
        处理来自其他 Agent 的异步任务委托。
        支持 sort-task / statistics-task。
        """
        logger.info(
            f"收到任务: task_id={task_id}, type={task_type}, "
            f"from={delegator_id}, input={input_data}"
        )

        if task_type == "sort-task":
            data = input_data.get("data", [])
            reverse = input_data.get("reverse", False)
            sorted_data = sorted(data, reverse=reverse)
            result = {"sorted": sorted_data, "task_id": task_id, "count": len(sorted_data)}
            logger.info(f"排序任务完成: task_id={task_id}")
            return result

        elif task_type == "statistics-task":
            data = input_data.get("data", [])
            if not data:
                return {"error": "data 为空", "task_id": task_id}
            total = sum(data)
            count = len(data)
            result = {
                "task_id": task_id,
                "sum": total,
                "count": count,
                "avg": total / count,
                "min": min(data),
                "max": max(data),
            }
            logger.info(f"统计任务完成: task_id={task_id}")
            return result

        else:
            raise ValueError(f"未知任务类型: {task_type}")

    # ---- 注册 PubSub 订阅（装饰器用法）----
    @agent.subscribe(["data.events", "system.broadcast"])
    async def handle_pubsub(topic: str, publisher_id: str, payload: dict):
        """处理 PubSub 事件"""
        logger.info(f"[PubSub] topic={topic}, from={publisher_id}, payload={payload}")

    # ---- 启动 Agent ----
    await agent.start()
    logger.info("Python Agent A 已启动，等待消息...")

    # 阻塞等待（Ctrl+C 退出）
    try:
        await agent.wait_for_termination()
    except KeyboardInterrupt:
        logger.info("收到退出信号，正在关闭...")
    finally:
        await agent.close()
        logger.info("Python Agent A 已关闭")


if __name__ == "__main__":
    asyncio.run(main())
