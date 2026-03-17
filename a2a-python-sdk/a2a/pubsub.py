"""
A2A SDK PubSub 订阅模块
提供带断线自动重连的异步 PubSub 订阅器
"""

import asyncio
import logging
from typing import Callable, Awaitable, Optional

import grpc

from a2a.proto import a2a_pb2, a2a_pb2_grpc

logger = logging.getLogger(__name__)

# PubSub 消息处理器类型：(topic, publisher_id, payload_dict) -> None
PubSubHandler = Callable[[str, str, dict], Awaitable[None]]


class PubSubSubscriber:
    """
    A2A PubSub 异步订阅器

    特性：
    - 订阅一个或多个 topic
    - 断线后指数退避自动重连
    - 支持优雅取消订阅

    用法::

        subscriber = PubSubSubscriber(channel, agent_id, session_token)
        await subscriber.subscribe(["topic.a", "topic.b"], my_handler)
        # ... 稍后 ...
        await subscriber.unsubscribe()
    """

    def __init__(
        self,
        channel: grpc.aio.Channel,
        agent_id: str,
        session_token: str,
    ):
        self._stub = a2a_pb2_grpc.PubSubServiceStub(channel)
        self._agent_id = agent_id
        self._session_token = session_token
        self._subscribe_task: Optional[asyncio.Task] = None
        self._running: bool = False

    async def subscribe(self, topics: list[str], handler: PubSubHandler) -> None:
        """
        开始订阅指定 topics，消息到达时调用 handler。
        断线后自动重连（指数退避，最大等待 30 秒）。

        :param topics: 要订阅的 topic 列表
        :param handler: 消息处理器 async (topic, publisher_id, payload) -> None
        """
        self._running = True
        self._subscribe_task = asyncio.create_task(
            self._subscribe_loop(topics, handler)
        )
        logger.info(f"PubSub 订阅已启动: agent_id={self._agent_id}, topics={topics}")

    async def _subscribe_loop(self, topics: list[str], handler: PubSubHandler) -> None:
        """
        订阅主循环，负责建立流式连接并处理消息。
        遇到连接错误时按指数退避重连。
        """
        attempt = 0

        while self._running:
            try:
                request = a2a_pb2.PubSubSubscribeRequest(
                    agent_id=self._agent_id,
                    topics=topics,
                    session_token=self._session_token,
                )

                logger.debug(f"PubSub 正在连接... (attempt={attempt})")

                # 建立服务端流式连接
                async for msg in self._stub.Subscribe(request):
                    if not self._running:
                        break

                    # 重置重试计数（说明连接正常）
                    attempt = 0

                    # 解析消息载荷
                    from a2a.utils import struct_to_dict
                    payload = struct_to_dict(msg.payload)

                    # 调用用户处理器
                    try:
                        await handler(msg.topic, msg.publisher_agent_id, payload)
                    except Exception as e:
                        logger.error(
                            f"PubSub handler 执行异常: topic={msg.topic}, error={e}",
                            exc_info=True,
                        )

            except asyncio.CancelledError:
                # 被主动取消，退出循环
                logger.debug("PubSub 订阅循环已取消")
                break

            except grpc.aio.AioRpcError as e:
                if not self._running:
                    break

                # 指数退避重连：1, 2, 4, 8, 16, 30, 30, ...
                wait = min(2 ** attempt, 30)
                logger.warning(
                    f"PubSub 连接断开，{wait}s 后重连... "
                    f"(attempt={attempt}, code={e.code()}, details={e.details()})"
                )
                try:
                    await asyncio.sleep(wait)
                except asyncio.CancelledError:
                    break
                attempt += 1

            except Exception as e:
                if not self._running:
                    break

                wait = min(2 ** attempt, 30)
                logger.error(
                    f"PubSub 发生未知错误，{wait}s 后重连... (attempt={attempt}): {e}",
                    exc_info=True,
                )
                try:
                    await asyncio.sleep(wait)
                except asyncio.CancelledError:
                    break
                attempt += 1

        logger.info(f"PubSub 订阅循环已退出: agent_id={self._agent_id}")

    async def unsubscribe(self) -> None:
        """
        取消订阅，停止重连循环。
        """
        self._running = False
        if self._subscribe_task and not self._subscribe_task.done():
            self._subscribe_task.cancel()
            try:
                await self._subscribe_task
            except asyncio.CancelledError:
                pass
        self._subscribe_task = None
        logger.info(f"PubSub 已取消订阅: agent_id={self._agent_id}")
