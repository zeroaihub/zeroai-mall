#!/usr/bin/env python3
"""Minimal stdlib MCP Streamable HTTP smoke client.

This intentionally uses no MCP SDK so it can verify the server protocol from a
second independent client implementation without adding project dependencies.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import time
import urllib.error
import urllib.request


ALLOWED_TOOLS = {
    "get_product_detail",
    "list_my_orders",
    "search_knowledge",
    "search_products",
}


def issue_token(secret: str, user_id: int, client_id: str, ttl_seconds: int) -> str:
    issued_at = int(time.time())
    expires_at = issued_at + ttl_seconds
    signing_input = f"v1.{user_id}.{client_id}.{issued_at}.{expires_at}"
    digest = hmac.new(
        secret.encode("utf-8"), signing_input.encode("utf-8"), hashlib.sha256
    ).digest()
    signature = base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")
    return f"{signing_input}.{signature}"


def parse_rpc_payload(content_type: str, body: str) -> dict:
    if "text/event-stream" not in content_type:
        return json.loads(body)
    for line in body.splitlines():
        if not line.startswith("data:"):
            continue
        data = line[5:].strip()
        if data and data != "[DONE]":
            return json.loads(data)
    raise RuntimeError("MCP response did not contain an SSE data payload")


class McpClient:
    def __init__(self, endpoint: str, token: str | None, timeout: int = 15):
        self.endpoint = endpoint
        self.token = token
        self.timeout = timeout
        self.session_id = ""
        self.protocol_version = "2025-03-26"
        self.request_id = 0

    def post(self, payload: dict) -> tuple[int, dict | None, dict]:
        headers = {
            "Accept": "application/json, text/event-stream",
            "Content-Type": "application/json",
        }
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        if self.session_id:
            headers["Mcp-Session-Id"] = self.session_id
        if self.protocol_version:
            headers["MCP-Protocol-Version"] = self.protocol_version
        request = urllib.request.Request(
            self.endpoint,
            data=json.dumps(payload).encode("utf-8"),
            headers=headers,
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                body = response.read().decode("utf-8")
                parsed = (
                    parse_rpc_payload(response.headers.get("Content-Type", ""), body)
                    if body.strip()
                    else None
                )
                return response.status, parsed, dict(response.headers)
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8", errors="replace")
            parsed = (
                parse_rpc_payload(error.headers.get("Content-Type", ""), body)
                if body.strip()
                else None
            )
            return error.code, parsed, dict(error.headers)

    def initialize(self) -> None:
        self.request_id += 1
        status, payload, headers = self.post(
            {
                "jsonrpc": "2.0",
                "id": self.request_id,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2025-03-26",
                    "capabilities": {},
                    "clientInfo": {
                        "name": "zeroai-python-smoke",
                        "version": "1.0.0",
                    },
                },
            }
        )
        if status != 200 or not payload or "error" in payload:
            raise RuntimeError(f"MCP initialize failed: HTTP {status}, {payload}")
        self.protocol_version = payload["result"]["protocolVersion"]
        self.session_id = headers.get("Mcp-Session-Id", "")
        self.post({"jsonrpc": "2.0", "method": "notifications/initialized"})

    def tools(self) -> list[dict]:
        self.request_id += 1
        status, payload, _ = self.post(
            {
                "jsonrpc": "2.0",
                "id": self.request_id,
                "method": "tools/list",
                "params": {},
            }
        )
        if status != 200 or not payload or "error" in payload:
            raise RuntimeError(f"tools/list failed: HTTP {status}, {payload}")
        return payload["result"]["tools"]

    def call_text(self, name: str, arguments: dict) -> str:
        self.request_id += 1
        status, payload, _ = self.post(
            {
                "jsonrpc": "2.0",
                "id": self.request_id,
                "method": "tools/call",
                "params": {"name": name, "arguments": arguments},
            }
        )
        if status != 200 or not payload or "error" in payload:
            raise RuntimeError(f"tools/call {name} failed: HTTP {status}, {payload}")
        return payload["result"]["content"][0]["text"]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", default="http://127.0.0.1:82/mcp")
    parser.add_argument("--secret", default=None)
    parser.add_argument("--user-id", type=int, default=1)
    parser.add_argument("--client-id", default="python-smoke")
    args = parser.parse_args()
    if not args.secret or len(args.secret.encode("utf-8")) < 32:
        raise SystemExit("--secret or MCP_TOKEN_SECRET must contain at least 32 UTF-8 bytes")

    token = issue_token(args.secret, args.user_id, args.client_id, 300)
    client = McpClient(args.endpoint, token)
    client.initialize()

    tools = client.tools()
    names = {tool["name"] for tool in tools}
    if names != ALLOWED_TOOLS:
        raise SystemExit(f"unexpected tools: {sorted(names)}")

    search = client.call_text("search_products", {"keyword": "phone", "limit": 1})
    if '"error"' in search:
        raise SystemExit(f"search_products failed: {search}")

    orders = client.call_text("list_my_orders", {"limit": 1})
    if "AUTH_REQUIRED" in orders or '"error"' in orders:
        raise SystemExit(f"list_my_orders failed: {orders}")

    tamper = client.call_text("list_my_orders", {"userId": 999999, "limit": 1})
    if not any(
        marker in tamper
        for marker in ("INVALID_ARGUMENTS", "validation failed", "未定义属性", "不允许附加属性")
    ):
        raise SystemExit(f"caller-supplied userId was accepted: {tamper}")

    unauthenticated = McpClient(args.endpoint, None)
    status, _, _ = unauthenticated.post(
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2025-03-26",
                "capabilities": {},
                "clientInfo": {"name": "negative-smoke", "version": "1.0.0"},
            },
        }
    )
    if status != 401:
        raise SystemExit(f"unauthenticated initialize returned HTTP {status}, expected 401")

    print(
        "MCP Python smoke passed: "
        f"protocol={client.protocol_version} tools={len(names)} "
        f"session={bool(client.session_id)} authRejected={status}"
    )


if __name__ == "__main__":
    main()
