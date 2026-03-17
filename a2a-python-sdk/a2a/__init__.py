"""
A2A Framework Python SDK
Agent-to-Agent 通信框架，基于 gRPC + asyncio
"""

from a2a.agent import A2AAgent
from a2a.client import A2AClient
from a2a.server import AgentServer
from a2a.config import A2AConfig
from a2a.types import (
    AgentInfo,
    AgentStatus,
    A2AMessage,
    MessageType,
    Priority,
    Task,
    TaskStatus,
)
from a2a.pubsub import PubSubSubscriber

__version__ = "1.0.0"

__all__ = [
    "A2AAgent",
    "A2AClient",
    "AgentServer",
    "A2AConfig",
    "AgentInfo",
    "AgentStatus",
    "A2AMessage",
    "MessageType",
    "Priority",
    "Task",
    "TaskStatus",
    "PubSubSubscriber",
]
