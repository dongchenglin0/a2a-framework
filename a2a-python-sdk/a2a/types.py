"""
A2A SDK 类型定义模块
包含所有枚举类型和数据类
"""

from dataclasses import dataclass, field
from enum import IntEnum
from typing import Any, Optional
from datetime import datetime


class AgentStatus(IntEnum):
    """Agent 状态枚举"""
    UNKNOWN = 0    # 未知
    ONLINE = 1     # 在线
    OFFLINE = 2    # 离线
    BUSY = 3       # 繁忙
    DRAINING = 4   # 排空中（准备下线）


class MessageType(IntEnum):
    """消息类型枚举"""
    REQUEST = 0        # 同步请求
    RESPONSE = 1       # 同步响应
    EVENT = 2          # 事件通知
    TASK_DELEGATE = 3  # 任务委托
    TASK_RESULT = 4    # 任务结果
    HEARTBEAT = 5      # 心跳
    ERROR = 6          # 错误


class Priority(IntEnum):
    """消息优先级枚举"""
    LOW = 0       # 低优先级
    NORMAL = 1    # 普通优先级
    HIGH = 2      # 高优先级
    CRITICAL = 3  # 紧急


class TaskStatus(IntEnum):
    """任务状态枚举"""
    TASK_PENDING = 0    # 等待执行
    TASK_RUNNING = 1    # 执行中
    TASK_SUCCESS = 2    # 执行成功
    TASK_FAILED = 3     # 执行失败
    TASK_CANCELLED = 4  # 已取消
    TASK_TIMEOUT = 5    # 超时


@dataclass
class AgentInfo:
    """Agent 信息数据类"""
    agent_id: str                                          # Agent 唯一标识
    agent_name: str                                        # Agent 名称
    agent_type: str                                        # Agent 类型
    host: str                                              # 主机地址
    port: int                                              # 监听端口
    status: AgentStatus = AgentStatus.UNKNOWN              # 当前状态
    capabilities: list[str] = field(default_factory=list)  # 能力列表
    metadata: dict[str, str] = field(default_factory=dict) # 元数据
    registered_at: Optional[datetime] = None               # 注册时间
    last_heartbeat: Optional[datetime] = None              # 最后心跳时间


@dataclass
class A2AMessage:
    """A2A 消息数据类"""
    message_id: str                                         # 消息唯一ID
    from_agent_id: str                                      # 发送方 Agent ID
    to_agent_id: str                                        # 接收方 Agent ID
    type: MessageType                                       # 消息类型
    payload: dict[str, Any] = field(default_factory=dict)  # 消息载荷
    headers: dict[str, str] = field(default_factory=dict)  # 消息头
    correlation_id: Optional[str] = None                   # 关联ID（用于请求/响应匹配）
    topic: Optional[str] = None                            # 主题（PubSub 使用）
    priority: Priority = Priority.NORMAL                   # 优先级
    created_at: Optional[datetime] = None                  # 创建时间


@dataclass
class Task:
    """任务数据类"""
    task_id: str                                              # 任务唯一ID
    task_type: str                                            # 任务类型
    delegator_agent_id: str                                   # 委托方 Agent ID
    executor_agent_id: str                                    # 执行方 Agent ID
    input: dict[str, Any] = field(default_factory=dict)      # 输入数据
    output: Optional[dict[str, Any]] = None                  # 输出数据
    status: TaskStatus = TaskStatus.TASK_PENDING              # 任务状态
    timeout_seconds: int = 60                                 # 超时时间（秒）
    context: dict[str, str] = field(default_factory=dict)    # 上下文信息
