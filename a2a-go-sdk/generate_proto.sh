#!/bin/bash
# 生成 Go protobuf 代码
# 用法: bash generate_proto.sh [proto_dir]
set -e

PROTO_DIR="${1:-../a2a-proto/src/main/proto}"
OUT_DIR="./proto"
PROTO_FILE="$PROTO_DIR/a2a.proto"

# 检查 proto 文件是否存在
if [ ! -f "$PROTO_FILE" ]; then
    echo "错误: proto 文件不存在: $PROTO_FILE"
    echo "请确认 a2a-proto 项目路径正确，或通过参数指定: bash generate_proto.sh <proto_dir>"
    exit 1
fi

# 创建输出目录
mkdir -p "$OUT_DIR"

echo "安装 protoc 插件..."
go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest

# 确保 GOPATH/bin 在 PATH 中
export PATH="$PATH:$(go env GOPATH)/bin"

echo "生成 Go protobuf 代码..."
echo "  proto 源目录: $PROTO_DIR"
echo "  输出目录:     $OUT_DIR"

protoc \
  --proto_path="$PROTO_DIR" \
  --go_out="$OUT_DIR" \
  --go_opt=paths=source_relative \
  --go-grpc_out="$OUT_DIR" \
  --go-grpc_opt=paths=source_relative \
  "$PROTO_FILE"

echo ""
echo "✅ Go proto 生成完成！文件在 $OUT_DIR/"
ls -la "$OUT_DIR/"*.go 2>/dev/null || echo "（暂无 .go 文件，请检查 protoc 输出）"
