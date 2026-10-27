#!/usr/bin/env python3
"""知识库问答冒烟：跑一条问题，打印工具调用与最终回答。

用途
----
评测脚本只测「检索层命中」，本脚本再往前一步，验证**模型会不会用命中结果**：
问题是否触发了 search_knowledge、回答里是否带 sourceId、知识库没有的东西是否说查不到。

登录走与 baseline 相同的旁路：往 Redis 写一个验证码再走真实 /login，
只绕过「人眼看验证码」这一步，token 签发、鉴权拦截都真实执行。

用法
----
    python3 scripts/agent/knowledge/ask_once.py "退款怎么申请"
    python3 scripts/agent/knowledge/ask_once.py "可以开发票吗" --mobile 13900000093
"""

from __future__ import annotations

import argparse
import http.client
import json
import socket
import sys
from urllib.parse import quote


def redis_set(host: str, port: int, key: str, value: str) -> None:
    """最小 RESP 客户端：只实现 SET，避免为一个调试脚本引入 redis 依赖。"""
    payload = f"*3\r\n$3\r\nSET\r\n${len(key)}\r\n{key}\r\n${len(value)}\r\n{value}\r\n".encode()
    with socket.create_connection((host, port), timeout=5) as sock:
        sock.sendall(payload)
        sock.recv(64)


def login(host: str, port: int, mobile: str, code: str, redis_port: int) -> str:
    redis_set("127.0.0.1", redis_port, f"zeroai:mobile_code:{mobile}_{code}", '"1"')
    conn = http.client.HTTPConnection(host, port, timeout=15)
    conn.request("POST", "/login", body=json.dumps({"mobile": mobile, "yzm": code}),
                 headers={"Content-Type": "application/json"})
    body = json.loads(conn.getresponse().read().decode("utf-8", "replace"))
    conn.close()
    if body.get("code") != 200 or not body.get("data"):
        raise SystemExit(f"登录失败：{body}")
    return body["data"]


def ask(host: str, port: int, token: str, question: str) -> None:
    conn = http.client.HTTPConnection(host, port, timeout=120)
    conn.request("GET", f"/ai/chat?q={quote(question)}", headers={
        "Authorization": token, "Accept": "text/event-stream"})
    response = conn.getresponse()
    event = None
    answer = []
    tools = []
    print(f"== 提问：{question}")
    while True:
        raw = response.readline()
        if not raw:
            break
        line = raw.decode("utf-8", "replace").rstrip("\r\n")
        if line.startswith("event:"):
            event = line[6:].strip()
        elif line.startswith("data:"):
            raw_data = line[5:].strip()
            try:
                payload = json.loads(raw_data)
            except json.JSONDecodeError:
                continue
            # 两种写法都兼容：`event:` 行 + 裸 data，或 单个 data 内含 {"type","data"}
            kind = payload.get("type") or event
            body = payload.get("data") if isinstance(payload.get("data"), dict) else payload
            if kind == "tool_result":
                tools.append(body.get("tool") or body.get("name"))
            elif kind == "message_delta":
                answer.append(body.get("delta") or "")
            elif kind in ("done", "error", "degrade"):
                print(f"   event={kind} data={json.dumps(body, ensure_ascii=False)[:200]}")
    conn.close()
    print(f"== 工具调用：{tools}")
    print(f"== 回答：{''.join(answer)}")


def main() -> int:
    parser = argparse.ArgumentParser(description="知识库问答冒烟")
    parser.add_argument("question")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=82)
    parser.add_argument("--mobile", default="13900000093")
    parser.add_argument("--code", default="123456")
    parser.add_argument("--redis-port", type=int, default=6379)
    args = parser.parse_args()

    token = login(args.host, args.port, args.mobile, args.code, args.redis_port)
    ask(args.host, args.port, token, args.question)
    return 0


if __name__ == "__main__":
    sys.exit(main())
