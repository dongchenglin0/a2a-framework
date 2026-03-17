# Proto 生成说明

本目录用于存放由 `a2a.proto` 生成的 Go protobuf 代码。

## 前置条件

- 已安装 `protoc`（Protocol Buffers 编译器）
- 已安装 Go 1.21+

## 安装 protoc 插件

```bash
go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest
```

确保 `$GOPATH/bin` 已加入 `PATH`：

```bash
export PATH="$PATH:$(go env GOPATH)/bin"
```

## 生成代码

在 SDK 根目录（`a2a-go-sdk/`）下运行：

```bash
protoc \
  --proto_path=../a2a-proto/src/main/proto \
  --go_out=./proto \
  --go_opt=paths=source_relative \
  --go-grpc_out=./proto \
  --go-grpc_opt=paths=source_relative \
  ../a2a-proto/src/main/proto/a2a.proto
```

或直接运行根目录下的脚本：

```bash
bash generate_proto.sh
```

## 生成文件说明

生成后，本目录将包含：

| 文件 | 说明 |
|------|------|
| `a2a.pb.go` | protobuf 消息类型定义 |
| `a2a_grpc.pb.go` | gRPC 服务接口定义 |

## 主要 gRPC 服务

| 服务 | 说明 |
|------|------|
| `RegistryService` | Agent 注册、注销、发现、心跳 |
| `MessagingService` | 同步请求/响应、单向消息、双向流 |
| `PubSubService` | 发布/订阅消息 |
| `TaskService` | 任务委托、状态查询、取消 |

## 注意事项

- 生成的文件不应手动修改，每次修改 `.proto` 后重新生成
- 如果 proto 文件路径有变化，请相应修改 `generate_proto.sh` 中的 `PROTO_DIR` 变量
