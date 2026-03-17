"""
A2A SDK 配置模块
"""

from dataclasses import dataclass, field
from typing import Optional


@dataclass
class A2AConfig:
    """A2A Agent 配置数据类"""

    # ---- Registry 地址 ----
    registry_host: str = "localhost"   # Registry 服务主机
    registry_port: int = 9090          # Registry 服务端口

    # ---- 本 Agent 信息 ----
    agent_id: str = ""                                         # Agent 唯一标识（必填）
    agent_name: str = ""                                       # Agent 显示名称
    agent_type: str = ""                                       # Agent 类型（用于服务发现）
    agent_host: str = "localhost"                              # 本 Agent 对外暴露的主机地址
    agent_port: int = 8000                                     # 本 Agent gRPC 监听端口
    capabilities: list[str] = field(default_factory=list)     # 能力列表（用于服务发现过滤）
    metadata: dict[str, str] = field(default_factory=dict)    # 自定义元数据

    # ---- 认证 ----
    jwt_token: str = ""   # JWT 认证令牌

    # ---- 连接配置 ----
    connect_timeout_ms: int = 5000       # 连接超时（毫秒）
    request_timeout_ms: int = 30000      # 请求超时（毫秒）
    heartbeat_interval_ms: int = 10000   # 心跳间隔（毫秒）
    auto_register: bool = True           # 启动时是否自动注册到 Registry
