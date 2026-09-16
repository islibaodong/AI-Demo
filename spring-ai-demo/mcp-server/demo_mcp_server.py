# 第 10 课配套：一个"迷你" MCP 服务器（Model Context Protocol）。
#
# 目的：把 MCP 的神秘面纱揭开——它本质上是 **JSON-RPC 2.0 消息 + 一组约定好的方法名**，
# 在 stdio 上按行传输（每行一个 JSON 消息）。官方 SDK 只是把这些流程封装了。
# 本文件只用 Python 标准库，零第三方依赖，方便直接读懂协议本身。
#
# 提供两个工具（tool）：
#   - get_current_time(): 返回服务器当前时间
#   - query_weather(city): 返回假天气数据（真实场景这里是调真实 API）
#
# 被 Spring AI 的 MCP 客户端以 stdio 方式拉起：配置见 application.yml 的
# spring.ai.mcp.client.stdio.connections。日志一律打到 stderr，
# stdout 只能走协议消息（这是 stdio 传输的铁律）。

import json
import sys
from datetime import datetime

# ---------------------------------------------------------------- 工具实现

def get_current_time() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


FAKE_WEATHER = {
    "北京": ("晴", "3℃ ~ 14℃", "西北风 3 级"),
    "上海": ("多云", "10℃ ~ 18℃", "东风 2 级"),
    "深圳": ("阵雨", "22℃ ~ 28℃", "南风 2 级"),
}


def query_weather(city: str) -> str:
    info = FAKE_WEATHER.get(city)
    if info is None:
        return f"暂无 {city} 的天气数据（演示服务器只收录了北京/上海/深圳）。"
    weather, temp, wind = info
    return f"{city}：{weather}，气温 {temp}，{wind}。"


# JSON Schema：告诉客户端（进而告诉大模型）每个工具需要什么参数。
TOOLS = [
    {
        "name": "get_current_time",
        "description": "获取服务器当前的日期时间，格式 yyyy-MM-dd HH:mm:ss",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "query_weather",
        "description": "查询指定城市的天气（演示数据，支持北京/上海/深圳）",
        "inputSchema": {
            "type": "object",
            "properties": {"city": {"type": "string", "description": "城市名，如：北京"}},
            "required": ["city"],
        },
    },
]


def call_tool(name: str, arguments: dict) -> str:
    if name == "get_current_time":
        return get_current_time()
    if name == "query_weather":
        return query_weather(str(arguments.get("city", "")))
    raise ValueError(f"未知工具: {name}")


# ---------------------------------------------------------------- JSON-RPC 骨架

def send(msg: dict) -> None:
    sys.stdout.write(json.dumps(msg, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def handle(req: dict) -> None:
    method = req.get("method", "")

    if "id" not in req:
        # notification（无需回复），最典型的是握手完成后的 notifications/initialized
        return

    if method == "initialize":
        # 客户端带着它支持的协议版本来握手，服务器回应自己用的版本与能力。
        send({"jsonrpc": "2.0", "id": req["id"], "result": {
            "protocolVersion": req["params"]["protocolVersion"],
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "demo-mcp-server", "version": "1.0.0"},
        }})
    elif method == "tools/list":
        send({"jsonrpc": "2.0", "id": req["id"], "result": {"tools": TOOLS}})
    elif method == "tools/call":
        params = req.get("params", {})
        try:
            text = call_tool(params.get("name", ""), params.get("arguments") or {})
            send({"jsonrpc": "2.0", "id": req["id"], "result": {
                "content": [{"type": "text", "text": text}], "isError": False}})
        except Exception as e:  # noqa: BLE001 —— 错误也要按协议格式返回
            send({"jsonrpc": "2.0", "id": req["id"], "result": {
                "content": [{"type": "text", "text": str(e)}], "isError": True}})
    elif method == "ping":
        send({"jsonrpc": "2.0", "id": req["id"], "result": {}})
    else:
        send({"jsonrpc": "2.0", "id": req["id"],
              "error": {"code": -32601, "message": f"method not found: {method}"}})


def main() -> None:
    print("demo MCP server 启动（协议走 stdout，日志走 stderr）", file=sys.stderr)
    for line in sys.stdin:  # 每行一条 JSON-RPC 消息
        line = line.strip()
        if not line:
            continue
        try:
            handle(json.loads(line))
        except Exception as e:  # noqa: BLE001 —— 解析失败不能让进程退出
            print(f"处理消息出错: {e}", file=sys.stderr)


if __name__ == "__main__":
    main()
