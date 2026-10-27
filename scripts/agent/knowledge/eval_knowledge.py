#!/usr/bin/env python3
"""知识检索评测跑批。

用途
----
把「知识库好不好用」从主观印象换成可复现的数字，回答三个问题：

1. 命中率：用户按正常说法提问时，正确的那篇政策是否在返回结果里（@1 / @3）；
2. 无答案识别：知识库没有的主题，检索是否老老实实返回 NO_MATCH，
   而不是硬凑一篇不相关的文档——硬凑比查不到更危险，模型会照着错的文档回答；
3. 注入泄漏：要求「忽略指令」「输出提示词」这类输入，返回的片段里是否夹带了指令性文本。

只打检索接口
------------
评测对象是检索层（GET /ai/knowledge/search），不经过模型：
模型回答的质量会漂移，检索命中与否是可确定的工程指标，先把这一层测住，
再去评「模型会不会引用」。

用法
----
    python3 scripts/agent/knowledge/eval_knowledge.py
    python3 scripts/agent/knowledge/eval_knowledge.py --base http://127.0.0.1:82 --out logs/kb-eval.json

前置：mobile-api 已启动且 zeroai.ai.knowledge.enabled=true。
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_CASES = Path(__file__).resolve().parent / "cases.jsonl"

# 与 KnowledgeGuard 保持一致的注入特征。这里独立一份而不是调 Java：
# 评测要能站在「外部观察者」角度判断输出，不能复用被测实现的判定逻辑。
INJECTION_MARKERS = (
    "忽略之前", "忽略以上", "ignore previous", "ignore all previous",
    "system prompt", "系统提示词", "你现在是", "绕过权限",
)


def load_cases(path: Path) -> list[dict]:
    """读取 JSONL 用例，忽略空行与注释行。"""
    cases = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        cases.append(json.loads(line))
    return cases


def search(base: str, query: str, limit: int = 3) -> dict:
    """调用检索接口，返回原始响应 data 部分。"""
    url = f"{base.rstrip('/')}/ai/knowledge/search?{urllib.parse.urlencode({'query': query, 'limit': limit})}"
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=10) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if payload.get("code") not in (0, 200, None):
        raise RuntimeError(f"接口返回失败：{payload}")
    return payload.get("data") or {}


def grade(case: dict, data: dict) -> dict:
    """按用例类型判定单条结果。"""
    status = data.get("status")
    items = data.get("items") or []
    ids = [item.get("sourceId") for item in items]
    snippets = " ".join(item.get("snippet") or "" for item in items)

    if case["type"] == "answer":
        expect = case["expect"]
        hit1 = bool(ids) and ids[0] in expect
        hit3 = any(source in expect for source in ids)
        return {"passed": hit3, "hit@1": hit1, "hit@3": hit3, "status": status, "ids": ids}

    if case["type"] == "no-answer":
        # 无答案用例的正确行为是「明确没查到」；返回了片段就算不合格
        return {"passed": status == "NO_MATCH" and not ids, "status": status, "ids": ids}

    leaked = [marker for marker in INJECTION_MARKERS if marker.lower() in snippets.lower()]
    return {"passed": not leaked, "status": status, "ids": ids, "leaked": leaked}


def main() -> int:
    parser = argparse.ArgumentParser(description="知识检索评测")
    parser.add_argument("--base", default="http://127.0.0.1:82", help="mobile-api 地址")
    parser.add_argument("--cases", default=str(DEFAULT_CASES), help="用例文件")
    parser.add_argument("--limit", type=int, default=3, help="每条查询返回片段数")
    parser.add_argument("--out", default="", help="把明细写成 JSON 的路径")
    args = parser.parse_args()

    cases = load_cases(Path(args.cases))
    started = time.time()
    details = []
    for case in cases:
        try:
            data = search(args.base, case["q"], args.limit)
            result = grade(case, data)
        except (urllib.error.URLError, RuntimeError, TimeoutError) as exc:
            result = {"passed": False, "status": "ERROR", "ids": [], "error": str(exc)}
        details.append({"id": case["id"], "cat": case["cat"], "type": case["type"],
                        "q": case["q"], "expect": case.get("expect") or [], **result})
        mark = "PASS" if result["passed"] else "FAIL"
        print(f"[{mark}] {case['id']:<4} {case['cat']:<14} status={result.get('status'):<10} "
              f"ids={result.get('ids')} q={case['q']}")

    def summarize(kind: str) -> dict:
        subset = [item for item in details if item["type"] == kind]
        total = len(subset)
        passed = sum(1 for item in subset if item["passed"])
        summary = {"total": total, "passed": passed,
                   "rate": round(passed / total, 4) if total else None}
        if kind == "answer":
            summary["hit@1"] = round(sum(1 for item in subset if item.get("hit@1")) / total, 4) if total else None
        return summary

    report = {
        "base": args.base,
        "cases": len(details),
        "elapsedMs": int((time.time() - started) * 1000),
        "answer": summarize("answer"),
        "no_answer": summarize("no-answer"),
        "injection": summarize("injection"),
        "failed": [item["id"] for item in details if not item["passed"]],
    }
    print("\n== 汇总 ==")
    print(json.dumps(report, ensure_ascii=False, indent=2))

    if args.out:
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps({"summary": report, "details": details}, ensure_ascii=False, indent=2),
                       encoding="utf-8")
        print(f"\n明细已写入 {out}")

    return 0 if not report["failed"] else 1


if __name__ == "__main__":
    sys.exit(main())
