"""
A2A SDK 工具函数模块
提供 protobuf 类型转换、ID 生成、时间戳处理等工具
"""

import uuid
from typing import Any

from google.protobuf.struct_pb2 import Struct, Value, ListValue
from google.protobuf.timestamp_pb2 import Timestamp
from datetime import datetime, timezone


def generate_id() -> str:
    """生成全局唯一 ID（UUID4）"""
    return str(uuid.uuid4())


def _value_to_python(value: Value) -> Any:
    """
    将单个 protobuf Value 转换为 Python 原生类型（内部递归辅助函数）

    :param value: protobuf Value 对象
    :return: Python 原生类型（str / int / float / bool / None / dict / list）
    """
    kind = value.WhichOneof("kind")
    if kind == "null_value":
        return None
    elif kind == "bool_value":
        return value.bool_value
    elif kind == "number_value":
        # 如果是整数则返回 int，否则返回 float
        num = value.number_value
        if num == int(num):
            return int(num)
        return num
    elif kind == "string_value":
        return value.string_value
    elif kind == "struct_value":
        return struct_to_dict(value.struct_value)
    elif kind == "list_value":
        return [_value_to_python(item) for item in value.list_value.values]
    else:
        return None


def _python_to_value(data: Any) -> Value:
    """
    将 Python 原生类型转换为 protobuf Value（内部递归辅助函数）

    :param data: Python 原生类型
    :return: protobuf Value 对象
    """
    v = Value()
    if data is None:
        v.null_value = 0  # NullValue.NULL_VALUE = 0
    elif isinstance(data, bool):
        # bool 必须在 int 之前判断，因为 bool 是 int 的子类
        v.bool_value = data
    elif isinstance(data, (int, float)):
        v.number_value = float(data)
    elif isinstance(data, str):
        v.string_value = data
    elif isinstance(data, dict):
        v.struct_value.CopyFrom(dict_to_struct(data))
    elif isinstance(data, (list, tuple)):
        list_val = ListValue()
        for item in data:
            list_val.values.append(_python_to_value(item))
        v.list_value.CopyFrom(list_val)
    else:
        # 其他类型转为字符串
        v.string_value = str(data)
    return v


def dict_to_struct(data: dict[str, Any]) -> Struct:
    """
    将 Python dict 转换为 protobuf Struct，支持递归嵌套。

    支持的值类型：
    - None → NullValue
    - bool → BoolValue
    - int / float → NumberValue
    - str → StringValue
    - dict → StructValue（递归）
    - list / tuple → ListValue（递归）
    - 其他 → 转为字符串

    :param data: Python 字典
    :return: protobuf Struct 对象
    """
    struct = Struct()
    for key, value in data.items():
        struct.fields[key].CopyFrom(_python_to_value(value))
    return struct


def struct_to_dict(struct: Struct) -> dict[str, Any]:
    """
    将 protobuf Struct 转换为 Python dict，支持递归嵌套。

    :param struct: protobuf Struct 对象
    :return: Python 字典
    """
    result: dict[str, Any] = {}
    for key, value in struct.fields.items():
        result[key] = _value_to_python(value)
    return result


def now_timestamp() -> Timestamp:
    """
    获取当前 UTC 时间的 protobuf Timestamp

    :return: protobuf Timestamp 对象
    """
    ts = Timestamp()
    ts.FromDatetime(datetime.now(timezone.utc))
    return ts


def timestamp_to_datetime(ts: Timestamp) -> datetime:
    """
    将 protobuf Timestamp 转换为带时区的 Python datetime（UTC）

    :param ts: protobuf Timestamp 对象
    :return: 带 UTC 时区的 datetime 对象
    """
    return ts.ToDatetime(tzinfo=timezone.utc)
