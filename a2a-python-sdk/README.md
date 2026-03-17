# fox-a2a-sdk — Python SDK

基于 gRPC + asyncio 的 A2A（Agent-to-Agent）通讯 Python SDK。

## 安装

```bash
pip install fox-a2a-sdk
```

或从源码安装：

```bash
cd a2a-python-sdk
pip install -e .
```

## 生成 Proto 代码

首次使用前需要生成 protobuf 代码：

```bash
# 安装 grpcio-tools
pip install grpcio-tools

# 生成代码
bash generate_proto.sh
```

生成后会在 `a2a/proto/` 目录下产生 `a2a_pb2.py` 和 `a2a_pb2_grpc.py`。

## 快速开始

### 启动 Registry

```bash
# 使用 Docker Compose
docker-compose up -d
```

### Agent A（服务提供方）

```python
import asyncio
from a2a import A2AAgent, A2AConfig

async def main():
    config = A2AConfig(
        registry_host="localhost",
        registry_port=9090,
        agent_id="my-agent",
        agent_name="我的Agent",
        agent_type="worker",
        agent_host="localhost",
        agent_port=8011,
        jwt_token="your-jwt-token",
    )

    agent = A2AAgent.create(config)

    # 处理同步请求（装饰器风格）
    @agent.on_request
    async def handle_request(from_agent_id: str, message_id: str, payload: dict) -> dict:
        numbers = payload.get("numbers", [])
        return {"sum": sum(numbers), "count": len(numbers)}

    # 处理异步任务
    @agent.on_task
    async def handle_task(task_id: str, task_type: str, delegator_id: str, input_data: dict) -> dict:
        data = sorted(input_data.get("data", []))
        return {"sorted": data}

    # 订阅 PubSub topic
    @agent.subscribe(["data.events"])
    async def on_event(topic: str, publisher_id: str, payload: dict):
        print(f"收到事件: {topic} from {publisher_id}")

    await agent.start()
    print("Agent 已启动，等待消息...")
    await agent.wait_for_termination()

asyncio.run(main())
```

### Agent B（调用方）

```python
import asyncio
from a2a import A2AAgent, A2AConfig

async def main():
    config = A2AConfig(
        agent_id="coordinator",
        agent_type="coordinator",
        agent_port=8012,
        jwt_token="your-jwt-token",
    )

    agent = A2AAgent.create(config)
    await agent.start()

    # 发现 Agent
    agents = await agent.discover("worker")
    print(f"发现 {len(agents)} 个 Agent")

    # 同步请求/响应
    result = await agent.send_request("my-agent", {"numbers": [1, 2, 3, 4, 5]})
    print(f"求和结果: {result}")

    # 委托任务
    task_id = await agent.delegate_task("my-agent", "sort-task", {"data": [5, 3, 1, 4, 2]})
    print(f"任务已委托: {task_id}")

    # 发布事件
    await agent.publish("data.events", {"event": "done"})

    await agent.close()

asyncio.run(main())
```

## API 文档

### A2AConfig

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `registry_host` | str | `"localhost"` | Registry 地址 |
| `registry_port` | int | `9090` | Registry gRPC 端口 |
| `agent_id` | str | 必填 | Agent 唯一ID |
| `agent_name` | str | `""` | Agent 名称 |
| `agent_type` | str | `""` | Agent 类型标签 |
| `agent_host` | str | `"localhost"` | 本 Agent 对外 host |
| `agent_port` | int | `8000` | 本 Agent gRPC 端口 |
| `capabilities` | list[str] | `[]` | 能力标签 |
| `jwt_token` | str | 必填 | JWT 认证 Token |
| `heartbeat_interval_ms` | int | `10000` | 心跳间隔（毫秒） |
| `auto_register` | bool | `True` | 启动时自动注册 |

### A2AAgent 方法

| 方法 | 说明 |
|------|------|
| `A2AAgent.create(config)` | 创建 Agent 实例 |
| `await agent.start()` | 启动 Agent（Server + 注册） |
| `await agent.close()` | 关闭 Agent |
| `agent.on_request(handler)` | 设置同步请求处理器（支持装饰器） |
| `agent.on_task(handler)` | 设置任务处理器（支持装饰器） |
| `agent.subscribe(topics)(handler)` | 订阅 PubSub topic（支持装饰器） |
| `await agent.discover(agent_type)` | 发现指定类型的 Agent |
| `await agent.send_request(to, payload)` | 同步请求/响应 |
| `await agent.send(to, topic, payload)` | 单向发送 |
| `await agent.publish(topic, payload)` | 发布到 PubSub |
| `await agent.delegate_task(to, type, input)` | 委托任务 |

## 通讯模式

| 模式 | 方法 | 说明 |
|------|------|------|
| 同步请求/响应 | `send_request()` | 等待对方返回结果 |
| 单向发送 | `send()` | fire-and-forget |
| 发布/订阅 | `publish()` / `subscribe()` | 多播事件 |
| 任务委托 | `delegate_task()` | 异步任务执行 |

## 环境变量

| 变量 | 说明 |
|------|------|
| `A2A_JWT_TOKEN` | Agent 认证 Token |

## 运行示例

```bash
# 先启动 Registry
docker-compose up -d

# 终端1：启动 Agent A（服务提供方）
python examples/agent_a_demo.py

# 终端2：启动 Agent B（调用方）
python examples/agent_b_demo.py
```

## 依赖

- Python 3.11+
- grpcio >= 1.62.0
- protobuf >= 4.25.0
- PyJWT >= 2.8.0
