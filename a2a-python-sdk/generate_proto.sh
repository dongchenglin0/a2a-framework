#!/bin/bash
# ============================================================
# generate_proto.sh
# 生成 A2A Python protobuf 代码
# 用法：在 a2a-python-sdk/ 目录下执行 bash generate_proto.sh
# ============================================================

set -e

PROTO_DIR="../a2a-proto/src/main/proto"
OUT_DIR="./a2a/proto"

# 检查 proto 文件是否存在
if [ ! -f "$PROTO_DIR/a2a.proto" ]; then
    echo "错误：未找到 proto 文件: $PROTO_DIR/a2a.proto"
    echo "请确认 a2a-proto 项目路径正确"
    exit 1
fi

# 确保输出目录存在
mkdir -p "$OUT_DIR"

# 获取 grpc_tools 内置 proto 路径（包含 google/protobuf/*.proto）
GRPC_TOOLS_PROTO=$(python -c 'import grpc_tools; import os; print(os.path.dirname(grpc_tools.__file__))')

echo "Proto 源目录: $PROTO_DIR"
echo "输出目录: $OUT_DIR"
echo "grpc_tools proto 路径: $GRPC_TOOLS_PROTO"
echo ""

# 生成 Python protobuf 代码
python -m grpc_tools.protoc \
    -I"$PROTO_DIR" \
    -I"$GRPC_TOOLS_PROTO/_proto" \
    --python_out="$OUT_DIR" \
    --grpc_python_out="$OUT_DIR" \
    "$PROTO_DIR/a2a.proto"

# 修复 grpc_python_out 生成的导入路径（将绝对导入改为包内相对导入）
if [ -f "$OUT_DIR/a2a_pb2_grpc.py" ]; then
    sed -i 's/^import a2a_pb2/from a2a.proto import a2a_pb2/g' "$OUT_DIR/a2a_pb2_grpc.py"
    echo "已修复 a2a_pb2_grpc.py 中的导入路径"
fi

echo ""
echo "✅ Proto 生成完成！"
echo "生成文件："
ls -la "$OUT_DIR/"*.py 2>/dev/null || echo "（无 .py 文件）"
