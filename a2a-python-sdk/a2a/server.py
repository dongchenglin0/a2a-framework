"""
A2A SDK 服务端模块
提供 gRPC 服务端实现，处理来自其他 Agent 的请求、消息和任务
"""

import asyncio
import logging
from typing import Callable, Optional, Awaitable

import grpc
from grpc import aio

from a2a.proto import a2a_pb2, a2a_pb2_grpc
from a2a.utils import dict_to_struct, struct_to_dict, generate_id, now_timestamp

logger = logging.getLogger(__name__)

# ------------------------------------------------------------------ #
#  类型别名
# ------------------------------------------------------------------ #

# 同步请求处理器：(from_agent_id, message_id, payload) -> response_dict
RequestHandler = Callable[[str, str, dict], Awaitable[dict]]

# 单向消息处理器：(from_agent_id, topic, payload) -> None
MessageHandler = Callable[[str, str, dict], Awaitable[None]]

# 任务处理器：(task_id, task_type, delegator_id, input_dict) -> output_dict
TaskHandler = Callable[[str, str, str, dict], Awaitable[dict]]


# ------------------------------------------------------------------ #
#  gRPC Messaging 服务实现
# ------------------------------------------------------------------ #

class _MessagingServicer(a2a_pb2_grpc.MessagingServiceServicer):
    """
    gRPC MessagingService 服务端实现
    处理同步请求（SendRequest）和单向消息（Send）
    """

    def __init__(
        self,
        request_handler: Optional[RequestHandler],
        message_handler: Optional[MessageHandler],
    ):
        self.request_handler = request_handler
        self.message_handler = message_handler

    async def SendRequest(self, request, context):
        """
        处理同步请求，调用 request_handler 并返回响应。
        若未设置 handler，返回空响应。
        """
        from_agent_id = request.from_agent_id
        message_id = request.message_id
        payload = struct_to_dict(request.payload)

        logger.debug(f"收到同步请求: from={from_agent_id}, message_id={message_id}")

        if self.request_handler is None:
            logger.warning("未设置 request_handler，返回空响应")
            return a2a_pb2.SendResponseMessage(
                message_id=generate_id(),
                correlation_id=message_id,
                from_agent_id="",
                payload=dict_to_struct({}),
                created_at=now_timestamp(),
            )

        try:
            response_payload = await self.request_handler(from_agent_id, message_id, payload)
        except Exception as e:
            logger.error(f"request_handler 执行异常: {e}", exc_info=True)
            context.set_code(grpc.StatusCode.INTERNAL)
            context.set_details(str(e))
            return a2a_pb2.SendResponseMessage(
                message_id=generate_id(),
                correlation_id=message_id,
                from_agent_id="",
                payload=dict_to_struct({"error": str(e)}),
                created_at=now_timestamp(),
            )

        return a2a_pb2.SendResponseMessage(
            message_id=generate_id(),
            correlation_id=message_id,
            from_agent_id="",
            payload=dict_to_struct(response_payload or {}),
            created_at=now_timestamp(),
        )

    async def Send(self, request, context):
        """
        处理单向消息，调用 message_handler（fire-and-forget）。
        """
        from_agent_id = request.from_agent_id
        topic = request.topic
        payload = struct_to_dict(request.payload)

        logger.debug(f"收到单向消息: from={from_agent_id}, topic={topic}")

        if self.message_handler is not None:
            try:
                await self.message_handler(from_agent_id, topic, payload)
            except Exception as e:
                logger.error(f"message_handler 执行异常: {e}", exc_info=True)

        return a2a_pb2.SendAck(
            message_id=request.message_id,
            received=True,
        )

    async def Stream(self, request_iterator, context):
        """
        双向流式传输（echo 模式，可按需扩展）。
        """
        async for chunk in request_iterator:
            yield chunk


# ------------------------------------------------------------------ #
#  gRPC Task 服务实现
# ------------------------------------------------------------------ #

class _TaskRecord:
    """任务记录，保存任务状态和异步执行句柄"""

    def __init__(self, proto_task: "a2a_pb2.Task"):
        self.proto_task = proto_task          # proto Task 对象（可变）
        self.asyncio_task: Optional[asyncio.Task] = None  # 异步执行任务


class _TaskServicer(a2a_pb2_grpc.TaskServiceServicer):
    """
    gRPC TaskService 服务端实现
    处理任务委托（DelegateTask）、状态查询（GetTaskStatus）、取消（CancelTask）
    """

    def __init__(self, task_handler: Optional[TaskHandler]):
        self.task_handler = task_handler
        self._tasks: dict[str, _TaskRecord] = {}  # task_id -> _TaskRecord

    async def DelegateTask(self, request, context):
        """
        接受任务委托，立即返回 accepted=True，异步执行任务。
        """
        task_id = request.task_id
        task_type = request.task_type
        delegator_id = request.delegator_agent_id
        input_data = struct_to_dict(request.input)

        logger.info(f"收到任务委托: task_id={task_id}, type={task_type}, from={delegator_id}")

        # 创建 proto Task 记录
        proto_task = a2a_pb2.Task(
            task_id=task_id,
            task_type=task_type,
            delegator_agent_id=delegator_id,
            executor_agent_id=request.executor_agent_id,
            input=request.input,
            status=a2a_pb2.TASK_PENDING,
            timeout_seconds=request.timeout_seconds,
            context=request.context,
        )
        record = _TaskRecord(proto_task)
        self._tasks[task_id] = record

        if self.task_handler is None:
            logger.warning(f"未设置 task_handler，任务 {task_id} 将标记为失败")
            proto_task.status = a2a_pb2.TASK_FAILED
            return a2a_pb2.DelegateTaskResponse(
                task_id=task_id,
                accepted=False,
                message="未设置 task_handler",
            )

        # 异步执行任务
        record.asyncio_task = asyncio.create_task(
            self._run_task(record, task_type, delegator_id, input_data, request.timeout_seconds)
        )

        return a2a_pb2.DelegateTaskResponse(
            task_id=task_id,
            accepted=True,
            message="任务已接受，异步执行中",
        )

    async def _run_task(
        self,
        record: _TaskRecord,
        task_type: str,
        delegator_id: str,
        input_data: dict,
        timeout_seconds: int,
    ) -> None:
        """异步执行任务，更新任务状态"""
        task_id = record.proto_task.task_id
        record.proto_task.status = a2a_pb2.TASK_RUNNING
        logger.debug(f"任务开始执行: task_id={task_id}")

        try:
            output = await asyncio.wait_for(
                self.task_handler(task_id, task_type, delegator_id, input_data),
                timeout=float(timeout_seconds),
            )
            record.proto_task.output.CopyFrom(dict_to_struct(output or {}))
            record.proto_task.status = a2a_pb2.TASK_SUCCESS
            logger.info(f"任务执行成功: task_id={task_id}")
        except asyncio.TimeoutError:
            record.proto_task.status = a2a_pb2.TASK_TIMEOUT
            logger.warning(f"任务超时: task_id={task_id}")
        except asyncio.CancelledError:
            record.proto_task.status = a2a_pb2.TASK_CANCELLED
            logger.info(f"任务已取消: task_id={task_id}")
        except Exception as e:
            record.proto_task.status = a2a_pb2.TASK_FAILED
            logger.error(f"任务执行失败: task_id={task_id}, error={e}", exc_info=True)

    async def GetTaskStatus(self, request, context):
        """查询任务状态"""
        task_id = request.task_id
        record = self._tasks.get(task_id)

        if record is None:
            context.set_code(grpc.StatusCode.NOT_FOUND)
            context.set_details(f"任务未找到: {task_id}")
            return a2a_pb2.GetTaskStatusResponse()

        return a2a_pb2.GetTaskStatusResponse(task=record.proto_task)

    async def CancelTask(self, request, context):
        """取消任务"""
        task_id = request.task_id
        record = self._tasks.get(task_id)

        if record is None:
            return a2a_pb2.CancelTaskResponse(
                task_id=task_id,
                success=False,
                message=f"任务未找到: {task_id}",
            )

        # 取消异步任务
        if record.asyncio_task and not record.asyncio_task.done():
            record.asyncio_task.cancel()
            try:
                await record.asyncio_task
            except asyncio.CancelledError:
                pass

        record.proto_task.status = a2a_pb2.TASK_CANCELLED
        logger.info(f"任务已取消: task_id={task_id}, reason={request.reason}")

        return a2a_pb2.CancelTaskResponse(
            task_id=task_id,
            success=True,
            message="任务已取消",
        )


# ------------------------------------------------------------------ #
#  AgentServer 门面类
# ------------------------------------------------------------------ #

class AgentServer:
    """
    A2A Agent gRPC 服务端

    提供链式 API 注册处理器，并管理 gRPC server 生命周期。

    用法::

        server = AgentServer(port=8011)
        server.on_request(my_request_handler)
        server.on_task(my_task_handler)
        await server.start()
        await server.wait_for_termination()
    """

    def __init__(self, port: int):
        self.port = port
        self._server: Optional[aio.Server] = None
        self._request_handler: Optional[RequestHandler] = None
        self._message_handler: Optional[MessageHandler] = None
        self._task_handler: Optional[TaskHandler] = None

    def on_request(self, handler: RequestHandler) -> "AgentServer":
        """
        注册同步请求处理器（支持装饰器用法）。

        :param handler: async (from_agent_id, message_id, payload) -> dict
        """
        self._request_handler = handler
        return self

    def on_message(self, handler: MessageHandler) -> "AgentServer":
        """
        注册单向消息处理器（支持装饰器用法）。

        :param handler: async (from_agent_id, topic, payload) -> None
        """
        self._message_handler = handler
        return self

    def on_task(self, handler: TaskHandler) -> "AgentServer":
        """
        注册任务处理器（支持装饰器用法）。

        :param handler: async (task_id, task_type, delegator_id, input_dict) -> dict
        """
        self._task_handler = handler
        return self

    async def start(self) -> None:
        """启动 gRPC 服务端，开始监听端口"""
        self._server = aio.server()

        # 注册 Messaging 服务
        a2a_pb2_grpc.add_MessagingServiceServicer_to_server(
            _MessagingServicer(self._request_handler, self._message_handler),
            self._server,
        )

        # 注册 Task 服务
        a2a_pb2_grpc.add_TaskServiceServicer_to_server(
            _TaskServicer(self._task_handler),
            self._server,
        )

        listen_addr = f"[::]:{self.port}"
        self._server.add_insecure_port(listen_addr)
        await self._server.start()
        logger.info(f"AgentServer 已启动，监听端口 {self.port}")

    async def stop(self) -> None:
        """优雅停止 gRPC 服务端（等待进行中的请求完成）"""
        if self._server:
            await self._server.stop(grace=5)
            logger.info(f"AgentServer 已停止（端口 {self.port}）")

    async def wait_for_termination(self) -> None:
        """阻塞等待服务端终止（通常用于主协程保活）"""
        if self._server:
            await self._server.wait_for_termination()
