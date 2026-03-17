"""
A2A SDK 客户端模块
提供与 Registry 和其他 Agent 通信的异步客户端
"""

import asyncio
import logging
from typing import Optional

import grpc

from a2a.config import A2AConfig
from a2a.types import AgentInfo, AgentStatus, Task, TaskStatus
from a2a.utils import dict_to_struct, struct_to_dict, generate_id, now_timestamp, timestamp_to_datetime

# 导入 proto 生成的 stub
from a2a.proto import a2a_pb2, a2a_pb2_grpc

logger = logging.getLogger(__name__)


class A2AClient:
    """
    A2A 异步 gRPC 客户端

    负责：
    - 连接 Registry 并注册/注销本 Agent
    - 服务发现（discover / get_agent）
    - 向其他 Agent 发送请求、消息、任务
    - 维护心跳
    - 管理 Agent 连接池
    """

    def __init__(self, config: A2AConfig):
        self.config = config
        self._registry_channel: Optional[grpc.aio.Channel] = None   # 到 Registry 的 gRPC channel
        self._registry_stub: Optional[a2a_pb2_grpc.RegistryServiceStub] = None
        self._pubsub_stub: Optional[a2a_pb2_grpc.PubSubServiceStub] = None
        self._agent_channels: dict[str, grpc.aio.Channel] = {}       # Agent 连接池
        self._session_token: str = ""                                  # 注册后获得的会话令牌
        self._heartbeat_task: Optional[asyncio.Task] = None

    # ------------------------------------------------------------------ #
    #  连接 / 注册 / 注销
    # ------------------------------------------------------------------ #

    async def connect(self) -> None:
        """
        建立到 Registry 的 gRPC 连接。
        必须在调用其他方法之前调用。
        """
        addr = f"{self.config.registry_host}:{self.config.registry_port}"
        self._registry_channel = grpc.aio.insecure_channel(addr)
        self._registry_stub = a2a_pb2_grpc.RegistryServiceStub(self._registry_channel)
        self._pubsub_stub = a2a_pb2_grpc.PubSubServiceStub(self._registry_channel)
        logger.info(f"已连接到 Registry: {addr}")

    async def register(self) -> None:
        """
        将本 Agent 注册到 Registry。
        注册成功后启动心跳协程，并保存 session_token。
        """
        if self._registry_stub is None:
            raise RuntimeError("请先调用 connect() 建立连接")

        # 构建 proto AgentInfo
        proto_info = a2a_pb2.AgentInfo(
            agent_id=self.config.agent_id,
            agent_name=self.config.agent_name,
            agent_type=self.config.agent_type,
            host=self.config.agent_host,
            port=self.config.agent_port,
            status=a2a_pb2.ONLINE,
            capabilities=self.config.capabilities,
            metadata=self.config.metadata,
        )

        request = a2a_pb2.RegisterRequest(
            agent_info=proto_info,
            jwt_token=self.config.jwt_token,
        )

        timeout = self.config.connect_timeout_ms / 1000
        response = await self._registry_stub.Register(request, timeout=timeout)

        if not response.success:
            raise RuntimeError(f"注册失败: {response.message}")

        self._session_token = response.session_token
        logger.info(f"Agent [{self.config.agent_id}] 注册成功，session_token={self._session_token}")

        # 启动心跳
        await self._start_heartbeat()

    async def deregister(self) -> None:
        """
        从 Registry 注销本 Agent。
        注销后停止心跳。
        """
        if self._registry_stub is None or not self._session_token:
            return

        try:
            request = a2a_pb2.DeregisterRequest(
                agent_id=self.config.agent_id,
                session_token=self._session_token,
            )
            timeout = self.config.connect_timeout_ms / 1000
            await self._registry_stub.Deregister(request, timeout=timeout)
            logger.info(f"Agent [{self.config.agent_id}] 已注销")
        except Exception as e:
            logger.warning(f"注销时发生错误（忽略）: {e}")
        finally:
            self._session_token = ""

    # ------------------------------------------------------------------ #
    #  服务发现
    # ------------------------------------------------------------------ #

    async def discover(
        self,
        agent_type: str = "",
        capabilities: list[str] | None = None,
    ) -> list[AgentInfo]:
        """
        发现符合条件的 Agent 列表。

        :param agent_type: 按 Agent 类型过滤（空字符串表示不过滤）
        :param capabilities: 按能力列表过滤（None 表示不过滤）
        :return: AgentInfo 列表
        """
        if self._registry_stub is None:
            raise RuntimeError("请先调用 connect() 建立连接")

        request = a2a_pb2.DiscoverRequest(
            agent_type=agent_type,
            capabilities=capabilities or [],
            session_token=self._session_token,
        )

        timeout = self.config.request_timeout_ms / 1000
        response = await self._registry_stub.Discover(request, timeout=timeout)

        agents: list[AgentInfo] = []
        for proto_agent in response.agents:
            agents.append(self._proto_to_agent_info(proto_agent))
        return agents

    async def get_agent(self, agent_id: str) -> Optional[AgentInfo]:
        """
        根据 agent_id 获取单个 Agent 的详细信息。

        :param agent_id: 目标 Agent ID
        :return: AgentInfo 或 None（未找到时）
        """
        if self._registry_stub is None:
            raise RuntimeError("请先调用 connect() 建立连接")

        request = a2a_pb2.GetAgentRequest(
            agent_id=agent_id,
            session_token=self._session_token,
        )

        timeout = self.config.request_timeout_ms / 1000
        try:
            response = await self._registry_stub.GetAgent(request, timeout=timeout)
            if response.HasField("agent_info"):
                return self._proto_to_agent_info(response.agent_info)
            return None
        except grpc.aio.AioRpcError as e:
            if e.code() == grpc.StatusCode.NOT_FOUND:
                return None
            raise

    # ------------------------------------------------------------------ #
    #  消息发送
    # ------------------------------------------------------------------ #

    async def send_request(
        self,
        to_agent_id: str,
        payload: dict,
        timeout_ms: int | None = None,
    ) -> dict:
        """
        向目标 Agent 发送同步请求并等待响应。

        :param to_agent_id: 目标 Agent ID
        :param payload: 请求载荷（Python dict）
        :param timeout_ms: 超时毫秒数（None 使用配置默认值）
        :return: 响应载荷（Python dict）
        """
        channel = await self._get_or_create_channel(to_agent_id)
        stub = a2a_pb2_grpc.MessagingServiceStub(channel)

        message_id = generate_id()
        request = a2a_pb2.SendRequestMessage(
            message_id=message_id,
            from_agent_id=self.config.agent_id,
            to_agent_id=to_agent_id,
            payload=dict_to_struct(payload),
            session_token=self._session_token,
            created_at=now_timestamp(),
        )

        timeout = (timeout_ms or self.config.request_timeout_ms) / 1000
        response = await stub.SendRequest(request, timeout=timeout)

        return struct_to_dict(response.payload)

    async def send(self, to_agent_id: str, topic: str, payload: dict) -> str:
        """
        向目标 Agent 单向发送消息（fire-and-forget）。

        :param to_agent_id: 目标 Agent ID
        :param topic: 消息主题
        :param payload: 消息载荷
        :return: 消息 ID
        """
        channel = await self._get_or_create_channel(to_agent_id)
        stub = a2a_pb2_grpc.MessagingServiceStub(channel)

        message_id = generate_id()
        request = a2a_pb2.SendMessage(
            message_id=message_id,
            from_agent_id=self.config.agent_id,
            to_agent_id=to_agent_id,
            topic=topic,
            payload=dict_to_struct(payload),
            session_token=self._session_token,
            created_at=now_timestamp(),
        )

        timeout = self.config.request_timeout_ms / 1000
        await stub.Send(request, timeout=timeout)
        logger.debug(f"消息已发送: message_id={message_id}, to={to_agent_id}, topic={topic}")
        return message_id

    async def publish(self, topic: str, payload: dict) -> str:
        """
        向 PubSub 发布消息到指定 topic。

        :param topic: 发布主题
        :param payload: 消息载荷
        :return: 消息 ID
        """
        if self._pubsub_stub is None:
            raise RuntimeError("请先调用 connect() 建立连接")

        message_id = generate_id()
        request = a2a_pb2.PublishRequest(
            message_id=message_id,
            publisher_agent_id=self.config.agent_id,
            topic=topic,
            payload=dict_to_struct(payload),
            session_token=self._session_token,
            created_at=now_timestamp(),
        )

        timeout = self.config.request_timeout_ms / 1000
        await self._pubsub_stub.Publish(request, timeout=timeout)
        logger.debug(f"事件已发布: message_id={message_id}, topic={topic}")
        return message_id

    # ------------------------------------------------------------------ #
    #  任务委托
    # ------------------------------------------------------------------ #

    async def delegate_task(
        self,
        to_agent_id: str,
        task_type: str,
        input_data: dict,
        timeout_seconds: int = 60,
    ) -> str:
        """
        向目标 Agent 委托任务，立即返回 task_id。

        :param to_agent_id: 执行方 Agent ID
        :param task_type: 任务类型
        :param input_data: 任务输入数据
        :param timeout_seconds: 任务超时时间（秒）
        :return: task_id
        """
        channel = await self._get_or_create_channel(to_agent_id)
        stub = a2a_pb2_grpc.TaskServiceStub(channel)

        task_id = generate_id()
        request = a2a_pb2.DelegateTaskRequest(
            task_id=task_id,
            task_type=task_type,
            delegator_agent_id=self.config.agent_id,
            executor_agent_id=to_agent_id,
            input=dict_to_struct(input_data),
            timeout_seconds=timeout_seconds,
            session_token=self._session_token,
        )

        timeout = self.config.request_timeout_ms / 1000
        response = await stub.DelegateTask(request, timeout=timeout)

        if not response.accepted:
            raise RuntimeError(f"任务委托被拒绝: task_id={task_id}, reason={response.message}")

        logger.info(f"任务已委托: task_id={task_id}, type={task_type}, to={to_agent_id}")
        return task_id

    async def get_task_status(self, task_id: str) -> Task:
        """
        查询任务状态。

        注意：此方法需要知道执行方 Agent，实际使用时建议通过 Registry 查询。
        当前实现通过遍历已建立的连接池查询。

        :param task_id: 任务 ID
        :return: Task 数据对象
        """
        # 遍历连接池中所有 Agent 查询任务状态
        for agent_id, channel in self._agent_channels.items():
            try:
                stub = a2a_pb2_grpc.TaskServiceStub(channel)
                request = a2a_pb2.GetTaskStatusRequest(
                    task_id=task_id,
                    session_token=self._session_token,
                )
                timeout = self.config.request_timeout_ms / 1000
                response = await stub.GetTaskStatus(request, timeout=timeout)
                return self._proto_to_task(response.task)
            except grpc.aio.AioRpcError as e:
                if e.code() == grpc.StatusCode.NOT_FOUND:
                    continue
                raise

        raise ValueError(f"未找到任务: task_id={task_id}")

    async def cancel_task(self, task_id: str, reason: str = "") -> bool:
        """
        取消指定任务。

        :param task_id: 任务 ID
        :param reason: 取消原因
        :return: 是否取消成功
        """
        for agent_id, channel in self._agent_channels.items():
            try:
                stub = a2a_pb2_grpc.TaskServiceStub(channel)
                request = a2a_pb2.CancelTaskRequest(
                    task_id=task_id,
                    reason=reason,
                    session_token=self._session_token,
                )
                timeout = self.config.request_timeout_ms / 1000
                response = await stub.CancelTask(request, timeout=timeout)
                if response.success:
                    logger.info(f"任务已取消: task_id={task_id}")
                    return True
            except grpc.aio.AioRpcError as e:
                if e.code() == grpc.StatusCode.NOT_FOUND:
                    continue
                raise

        logger.warning(f"取消任务失败，未找到任务: task_id={task_id}")
        return False

    # ------------------------------------------------------------------ #
    #  内部方法
    # ------------------------------------------------------------------ #

    async def _start_heartbeat(self) -> None:
        """启动心跳协程，定期向 Registry 发送心跳"""

        async def _heartbeat_loop():
            while True:
                await asyncio.sleep(self.config.heartbeat_interval_ms / 1000)
                try:
                    await self._registry_stub.Heartbeat(
                        a2a_pb2.HeartbeatRequest(
                            agent_id=self.config.agent_id,
                            session_token=self._session_token,
                            status=a2a_pb2.ONLINE,
                            timestamp=now_timestamp(),
                        )
                    )
                    logger.debug(f"心跳发送成功: agent_id={self.config.agent_id}")
                except asyncio.CancelledError:
                    break
                except Exception as e:
                    logger.warning(f"心跳失败: {e}")

        self._heartbeat_task = asyncio.create_task(_heartbeat_loop())
        logger.debug("心跳协程已启动")

    async def _get_or_create_channel(self, agent_id: str) -> grpc.aio.Channel:
        """
        从连接池获取或新建到目标 Agent 的 gRPC channel。

        :param agent_id: 目标 Agent ID
        :return: gRPC channel
        """
        if agent_id not in self._agent_channels:
            agent = await self.get_agent(agent_id)
            if not agent:
                raise ValueError(f"Agent 未找到: {agent_id}")
            addr = f"{agent.host}:{agent.port}"
            self._agent_channels[agent_id] = grpc.aio.insecure_channel(addr)
            logger.debug(f"已建立到 Agent [{agent_id}] 的连接: {addr}")
        return self._agent_channels[agent_id]

    @staticmethod
    def _proto_to_agent_info(proto: "a2a_pb2.AgentInfo") -> AgentInfo:
        """将 proto AgentInfo 转换为 Python AgentInfo 数据类"""
        return AgentInfo(
            agent_id=proto.agent_id,
            agent_name=proto.agent_name,
            agent_type=proto.agent_type,
            host=proto.host,
            port=proto.port,
            status=AgentStatus(proto.status),
            capabilities=list(proto.capabilities),
            metadata=dict(proto.metadata),
            registered_at=timestamp_to_datetime(proto.registered_at) if proto.HasField("registered_at") else None,
            last_heartbeat=timestamp_to_datetime(proto.last_heartbeat) if proto.HasField("last_heartbeat") else None,
        )

    @staticmethod
    def _proto_to_task(proto: "a2a_pb2.Task") -> Task:
        """将 proto Task 转换为 Python Task 数据类"""
        from a2a.utils import struct_to_dict
        return Task(
            task_id=proto.task_id,
            task_type=proto.task_type,
            delegator_agent_id=proto.delegator_agent_id,
            executor_agent_id=proto.executor_agent_id,
            input=struct_to_dict(proto.input),
            output=struct_to_dict(proto.output) if proto.HasField("output") else None,
            status=TaskStatus(proto.status),
            timeout_seconds=proto.timeout_seconds,
            context=dict(proto.context),
        )

    async def close(self) -> None:
        """关闭所有连接，停止心跳，注销 Agent"""
        # 停止心跳
        if self._heartbeat_task:
            self._heartbeat_task.cancel()
            try:
                await self._heartbeat_task
            except asyncio.CancelledError:
                pass
            self._heartbeat_task = None

        # 注销
        await self.deregister()

        # 关闭 Registry channel
        if self._registry_channel:
            await self._registry_channel.close()
            self._registry_channel = None

        # 关闭所有 Agent channel
        for agent_id, ch in self._agent_channels.items():
            try:
                await ch.close()
            except Exception as e:
                logger.warning(f"关闭 Agent [{agent_id}] 连接时出错: {e}")
        self._agent_channels.clear()

        logger.info(f"A2AClient [{self.config.agent_id}] 已关闭")
