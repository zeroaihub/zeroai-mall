"""本地假 embedding 服务：只用于验证向量检索链路，不提供真实语义能力。

为什么需要它：DeepSeek 没有 embedding 接口，本地也没跑 Ollama，但要验证
「切块 → 向量化 → 建索引 → 混合融合 → 降级回退」这条链路是否真的通，
就必须有一个能返回固定维度向量的服务。

它用字符 uni-gram + bi-gram 做 feature hashing：相似文本会得到相似向量，
所以「字面相近的问题能召回」这个方向是对的；但它不懂语义，
**不能用它评估召回质量**。语义效果要用真模型跑 scripts/agent/knowledge/eval_knowledge.py。

用法：
    python scripts/agent/knowledge/fake_embedding_server.py --port 18080 --dimension 256

配合启动：
    AI_KNOWLEDGE_MODE=hybrid \
    AI_EMBEDDING_BASE_URL=http://127.0.0.1:18080 \
    AI_EMBEDDING_MODEL=fake-hash-256 \
    AI_EMBEDDING_DIMENSION=256 \
    java -jar zeroai-mobile-api/target/*.jar
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def embed(text: str, dimension: int) -> list[float]:
    """把文本映射成固定维度向量（确定性，同文本永远同向量）。"""
    vector = [0.0] * dimension
    for index, char in enumerate(text):
        grams = [char]
        if index + 1 < len(text):
            grams.append(text[index:index + 2])
        for gram in grams:
            digest = hashlib.md5(gram.encode("utf-8")).hexdigest()
            vector[int(digest[:8], 16) % dimension] += 1.0
    return vector


class Handler(BaseHTTPRequestHandler):
    dimension = 256

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler 约定
        if not self.path.rstrip("/").endswith("/v1/embeddings"):
            self._send(404, {"error": f"unknown path {self.path}"})
            return
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8") if length else "{}"
        try:
            body = json.loads(raw)
        except json.JSONDecodeError:
            self._send(400, {"error": "invalid json"})
            return

        inputs = body.get("input")
        if isinstance(inputs, str):
            inputs = [inputs]
        if not isinstance(inputs, list) or not inputs:
            self._send(400, {"error": "input must be a non-empty string or array"})
            return

        data = [
            {"index": i, "embedding": embed(str(text), self.dimension)}
            for i, text in enumerate(inputs)
        ]
        self._send(200, {"model": body.get("model", "fake-hash"), "data": data,
                         "usage": {"prompt_tokens": 0, "total_tokens": 0}})

    def _send(self, status: int, payload: dict) -> None:
        encoded = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, fmt: str, *args) -> None:
        sys.stderr.write("[fake-embedding] " + fmt % args + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description="假 embedding 服务（仅验证链路）")
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--dimension", type=int, default=256)
    args = parser.parse_args()

    Handler.dimension = args.dimension
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"fake embedding server listening on http://127.0.0.1:{args.port} dimension={args.dimension}")
    server.serve_forever()


if __name__ == "__main__":
    main()
