"""
A2A SDK Agent 门面模块
整合 Client（对外通信）+ Server（接收请求）+ PubSub（事件订阅）
提供统一的高层 API
"""

import asyncio
import logging
from typing import Optional

from a2a.config import A2AConfig
from a2a.client import A2AClient
from a2a.server import AgentServer, RequestHandler, MessageHandler, TaskHandler
from a2a.pubsub import PubSubSubscriber, PubSubHandler
from a2a.types import AgentInfo, Task

logger = logging.getLogger(__name__)


class A2AAgent:
    """
    A2A Agent 门面类

    整合了：
    - AgentServer：接收来自其他 Agent 的请求/消息/任务
    - A2AClient：向 Registry 注册、发现其他 Agent、发送请求/任务
    - PubSubSubscriber：订阅 PubSub 事件

    支持链式 API 和装饰器风格注册处理器。

    用法::

        config = A2AConfig(agent_id="my-agent", ...)
        agent = A2AAgent.create(config)

        @agent.on_request
        async def handle_request(from_id, msg_id, payload):
            return {"result": "ok"}

        await agent.start()
        await agent.wait_for_termination()
    """

    def __init__(self, config: A2AConfig):
        self.config = config
        self.client = A2AClient(config)
        self.server = AgentServer(config.agent_port)
        self._pubsub: Optional[PubSubSubscriber] = None
        self._subscribed_topics: list[str] = []
        self._pubsub_handler: Optional[PubSubHandler] = None

    @classmethod
    def create(cls, config: A2AConfig) -> "A2AAgent":
        """
        工厂方法，创建 A2AAgent 实例。

        :param config: A2AConfig 配置对象
        :return: A2AAgent 实例
        """
        return cls(config)

    # ------------------------------------------------------------------ #
    #  处理器注册（支持装饰器用法）
    # ------------------------------------------------------------------ #

    def on_request(self, handler: RequestHandler) -> "A2AAgent":
        """
        注册同步请求处理器。

        支持两种用法：

        1. 直接调用::

            agent.on_request(my_handler)

        2. 装饰器::

            @agent.on_request
            async def my_handler(from_id, msg_id, payload):
                return {"result": "ok"}

        :param handler: async (from_agent_id, message_id, payload) -> dict
        :return: self（支持链式调用）
        """
        self.server.on_request(handler)
        return self

    def on_message(self, handler: MessageHandler) -> "A2AAgent":
        """
        注册单向消息处理器。

        :param handler: async (from_agent_id, topic, payload) -> None
        :return: self（支持链式调用）
        """
        self.server.on_message(handler)
        return self

    def on_task(self, handler: TaskHandler) -> "A2AAgent":
        """
        注册任务处理器。

        :param handler: async (task_id, task_type, delegator_id, input_dict) -> dict
        :return: self（支持链式调用）
        """
        self.server.on_task(handler)
        return self

    def subscribe(self, topics: list[str]) -> "A2AAgent":
        """
        注册 PubSub 订阅（装饰器用法）。

        用法::

            @agent.subscribe(["topic.a", "topic.b"])
            async def handle_event(topic, publisher_id, payload):
                print(f"收到事件: {topic}")

        :param topics: 要订阅的 topic 列表
        :return: 装饰器函数（接受 handler 并返回 self）
        """
        def decorator(handler: PubSubHandler) -> "A2AAgent":
            self._subscribed_topics = topics
            self._pubsub_handler = handler
            return self
        return decorator  # type: ignore[return-value]

    def set_pubsub_handler(self, topics: list[str], handler: PubSubHandler) -> "A2AAgent":
        """
        直接设置 PubSub 处理器（非装饰器用法）。

        :param topics: 要订阅的 topic 列表
        :param handler: async (topic, publisher_id, payload) -> None
        :return: self（支持链式调用）
        """
        self._subscribed_topics = topics
        self._pubsub_handler = handler
        return self

    # ------------------------------------------------------------------ #
    #  生命周期
    # ------------------------------------------------------------------ #

    async def start(self) -> None:
        """
        启动 Agent：
        1. 启动 gRPC 服务端（开始接收请求）
        2. 连接 Registry
        3. 注册本 Agent（如果 auto_register=True）
        4. 启动 PubSub 订阅（如果已配置）
        """
        # 1. 启动 gRPC 服务端
        await self.server.start()

        # 2. 连接 Registry
        await self.client.connect()

        # 3. 注册
        if self.config.auto_register:
            await self.client.register()

        # 4. 启动 PubSub 订阅
        if self._subscribed_topics and self._pubsub_handler:
            self._pubsub = PubSubSubscriber(
                channel=self.client._registry_channel,
                agent_id=self.config.agent_id,
                session_token=self.client._session_token,
            )
            await self._pubsub.subscribe(self._subscribed_topics, self._pubsub_handler)

        logger.info(f"A2AAgent [{self.config.agent_id}] 已启动")

    async def close(self) -> None:
        """
        优雅关闭 Agent：
        1. 取消 PubSub 订阅
        2. 关闭 Client（注销 + 关闭连接）
        3. 停止 gRPC 服务端
        """
        # 1. 取消 PubSub 订阅
        if self._pubsub:
            await self._pubsub.unsubscribe()
            self._pubsub = None

        # 2. 关闭 Client
        await self.client.close()

        # 3. 停止服务端
        await self.server.stop()

        logger.info(f"A2AAgent [{self.config.agent_id}] 已关闭")

    async def wait_for_termination(self) -> None:
        """阻塞等待服务端终止（通常用于主协程保活）"""
        await self.server.wait_for_termination()

    # ------------------------------------------------------------------ #
    #  代理 Client 方法（便捷访问）
    # ------------------------------------------------------------------ #

    async def discover(self, agent_type: str = "") -> list[AgentInfo]:
        """
        发现指定类型的 Agent 列表。

        :param agent_type: Agent 类型过滤（空字符串表示不过滤）
        :return: AgentInfo 列表
        """
        return await self.client.discover(agent_type)

    async def send_request(self, to_agent_id: str, payload: dict) -> dict:
        """
        向目标 Agent 发送同步请求并等待响应。

        :param to_agent_id: 目标 Agent ID
        :param payload: 请求载荷
        :return: 响应载荷
        """
        return await self.client.send_request(to_agent_id, payload)

    async def send(self, to_agent_id: str, topic: str, payload: dict) -> str:
        """
        向目标 Agent 单向发送消息（fire-and-forget）。

        :param to_agent_id: 目标 Agent ID
        :param topic: 消息主题
        :param payload: 消息载荷
        :return: 消息 ID
        """
        return await self.client.send(to_agent_id, topic, payload)

    async def publish(self, topic: str, payload: dict) -> str:
        """
        向 PubSub 发布事件。

        :param topic: 发布主题
        :param payload: 事件载荷
        :return: 消息 ID
        """
        return await self.client.publish(topic, payload)

    async def delegate_task(
        self,
        to_agent_id: str,
        task_type: str,
        input_data: dict,
        timeout_seconds: int = 60,
    ) -> str:
        """
        向目标 Agent 委托任务。

        :param to_agent_id: 执行方 Agent ID
        :param task_type: 任务类型
        :param input_data: 任务输入数据
        :param timeout_seconds: 任务超时时间（秒）
        :return: task_id
        """
        return await self.client.delegate_task(to_agent_id, task_type, input_data, timeout_seconds)
