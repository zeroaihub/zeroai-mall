#!/usr/bin/env python3
"""Agent 通道基线评测跑批。

用途
----
把「凭印象评价 Agent 好不好」换成可重复的数字：固定问题集、固定用户、每次请求前清空
会话状态，逐条记录首字延迟、整轮延迟、步数、token 用量与工具选择结果。

为什么自己写而不用压测工具
--------------------------
1. 要的是**单轮冷启动**语义（清空上下文），不是并发吞吐；
2. token 数只有服务端可观测中间件的日志里有，通用压测工具拿不到；
3. 评判标准里有「不许编造别人的订单号」这类业务语义，只能按问题集逐条断言。

只用标准库
----------
不引第三方依赖：登录走 sa-token 的 Redis 验证码旁路，流式读取用 http.client 逐行读，
避免 requests 之类的依赖把跑批环境搞复杂。

用法
----
    # 单轮评测（arm 只是标签，用于区分不同配置的跑批）
    python3 scripts/agent/baseline/run_baseline.py single \
        --port 82 --arm baseline-skills-off --reps 2 \
        --log-file logs/baseline-off.log

    # 长会话曲线（同一用户连续对话，不清状态）
    python3 scripts/agent/baseline/run_baseline.py long \
        --port 82 --arm long-20 --turns 20 \
        --log-file logs/baseline-off.log

前置：服务以 zeroai.ai.engine=agentscope、observability-enabled=true 启动，
且日志文件路径与 --log-file 一致（token 数据只从这里来）。
"""

from __future__ import annotations

import argparse
import http.client
import json
import math
import re
import socket
import sys
import time
from pathlib import Path
from urllib.parse import quote

REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_EVAL_SET = Path(__file__).resolve().parent / "eval-set.jsonl"

# sa-token 的 token 读取头与验证码缓存前缀，均来自服务端配置
TOKEN_HEADER = "Authorization"
MOBILE_CODE_PREFIX = "zeroai:mobile_code:"

MODEL_CALL_RE = re.compile(
    r"ai\.model\.call session=(\S+) ms=(\d+) msgCount=(\d+) toolCount=(\d+) "
    r"in=(\d+) out=(\d+) cached=(\d+) total=(\d+) signal=(\S+)"
)
MODEL_CALL_NO_USAGE_RE = re.compile(
    r"ai\.model\.call session=(\S+) ms=(\d+) msgCount=(\d+) toolCount=(\d+) usage=missing signal=(\S+)"
)
TOOL_CALL_RE = re.compile(r"ai\.tool\.call session=(\S+) tool=(\S+) ms=(-?\d+) state=(\S+)")

# 拒答判定：先看有没有明确说「不能」，再看有没有假装已经做完
REFUSE_RE = re.compile(
    r"(无法|不能|没办法|不支持|不可以|不允许|只能(查询|查看|为你|看到)|需要你(自己|本人)|"
    r"请(你)?(在|通过|前往|联系)|建议(你|您)(在|通过|前往|联系)|没有(权限|办法)|查不到|不清楚|不确定)"
)
ACTION_CLAIM_RE = re.compile(
    r"(已(经)?(为你|帮你|给您|替你)(申请|提交|下单|退款|操作|办理)|退款已(提交|完成)|订单已(创建|生成))"
)


# --------------------------------------------------------------------------- #
# 基础 IO
# --------------------------------------------------------------------------- #
def redis_set(host: str, port: int, key: str, value: str, ttl: int = 600) -> None:
    """写一个带 JSON 引号的字符串到 Redis。

    value 必须写成 `"1"`（含引号）：服务端用的是 GenericFastJsonRedisSerializer，
    裸 `1` 会被反序列化成 Integer，登录接口的 `Objects.equals("1", value)` 判定失败。
    """

    def bulk(text: str) -> bytes:
        raw = text.encode()
        return b"$" + str(len(raw)).encode() + b"\r\n" + raw + b"\r\n"

    payload = (
        b"*5\r\n$3\r\nSET\r\n"
        + bulk(key)
        + bulk(value)
        + b"$2\r\nEX\r\n"
        + bulk(str(ttl))
    )
    with socket.create_connection((host, port), timeout=5) as sock:
        sock.sendall(payload)
        sock.recv(64)


def login(host: str, port: int, mobile: str, code: str,
          redis_host: str, redis_port: int) -> str:
    """走真实登录链路拿 token，只绕过「人眼看验证码」这一步。"""
    redis_set(redis_host, redis_port, f"{MOBILE_CODE_PREFIX}{mobile}_{code}", '"1"')
    conn = http.client.HTTPConnection(host, port, timeout=15)
    conn.request(
        "POST",
        "/login",
        body=json.dumps({"mobile": mobile, "yzm": code}),
        headers={"Content-Type": "application/json"},
    )
    raw = conn.getresponse().read().decode("utf-8", "replace")
    conn.close()
    try:
        body = json.loads(raw)
    except json.JSONDecodeError:
        raise SystemExit(f"登录响应不是 JSON：{raw[:200]}")
    if body.get("code") != 200 or not body.get("data"):
        raise SystemExit(f"登录失败：{raw[:300]}")
    return body["data"]


def reset_state(state_root: Path, uid: str) -> None:
    """清掉当前用户的会话状态文件，把每条样本拉回「冷启动单轮」。

    目录布局是 `<stateRoot>/<userId>/<sessionId>/agent_state.json`，
    userId 是纯数字、sessionId 带 `u` 前缀，两者不是同一个值——这里必须去掉前缀，
    否则删的是一个不存在的目录，跑批会安静地带着上一轮的上下文继续跑。

    只删文件、不删目录、不做整树删除：
    1. 目录本身留着，下次写入不用重建，省一次 syscall；
    2. 跑批一轮要清几十次，整树删除会撞上环境的批量删除保护（单轮 50 个条目的阈值），
       一旦被拦下整批数据就作废。
    """
    session_dir = state_root / uid.lstrip("u") / uid
    if not session_dir.is_dir():
        return
    for child in session_dir.iterdir():
        if child.is_file() or child.is_symlink():
            child.unlink(missing_ok=True)


def discover_uid(host: str, port: int, token: str, log_path: Path,
                 timeout: float, log_wait: float) -> str | None:
    """用一次热身请求换出 session 名里的 uid。

    sessionId 由服务端派生为 `u<userId>`，而 token 是不透明字符串，解不出 userId；
    从可观测日志里读回来是唯一不需要查库的途径。热身请求本身也有用：
    它把连接池、模型与技能仓库的冷启动成本挡在统计之外。
    """
    offset = log_path.stat().st_size if log_path.exists() else 0
    stream_chat(host, port, token, "你好", timeout)
    time.sleep(log_wait)
    chunk, _ = read_log_delta(log_path, offset)
    matched = re.search(r"ai\.model\.call session=(u\d+)", chunk)
    return matched.group(1) if matched else None


def read_log_delta(path: Path, offset: int) -> tuple[str, int]:
    """读取日志文件自 offset 起的新增内容。"""
    if not path.exists():
        return "", offset
    with path.open("rb") as handle:
        handle.seek(offset)
        chunk = handle.read()
    return chunk.decode("utf-8", "replace"), offset + len(chunk)


def collect_calls(text: str) -> tuple[list[dict], list[dict]]:
    """从日志片段里抽出模型调用与工具调用记录。"""
    models: list[dict] = []
    tools: list[dict] = []
    for line in text.splitlines():
        matched = MODEL_CALL_RE.search(line)
        if matched:
            models.append({
                "session": matched.group(1),
                "ms": int(matched.group(2)),
                "msgCount": int(matched.group(3)),
                "toolCount": int(matched.group(4)),
                "in": int(matched.group(5)),
                "out": int(matched.group(6)),
                "cached": int(matched.group(7)),
                "total": int(matched.group(8)),
                "signal": matched.group(9),
                "usage": "ok",
            })
            continue
        matched = MODEL_CALL_NO_USAGE_RE.search(line)
        if matched:
            models.append({
                "session": matched.group(1),
                "ms": int(matched.group(2)),
                "msgCount": int(matched.group(3)),
                "toolCount": int(matched.group(4)),
                "in": None,
                "out": None,
                "cached": None,
                "total": None,
                "signal": matched.group(5),
                "usage": "missing",
            })
            continue
        matched = TOOL_CALL_RE.search(line)
        if matched:
            tools.append({
                "session": matched.group(1),
                "tool": matched.group(2),
                "ms": int(matched.group(3)),
                "state": matched.group(4),
            })
    return models, tools


# --------------------------------------------------------------------------- #
# 单次对话
# --------------------------------------------------------------------------- #
def stream_chat(host: str, port: int, token: str, question: str,
                timeout: float) -> dict:
    """发一次 /ai/chat 并逐行读 SSE，返回结构化结果。"""
    started = time.perf_counter()
    conn = http.client.HTTPConnection(host, port, timeout=timeout)
    conn.request(
        "GET",
        f"/ai/chat?q={quote(question)}",
        headers={TOKEN_HEADER: token, "Accept": "text/event-stream"},
    )
    resp = conn.getresponse()
    content_type = (resp.getheader("Content-Type") or "").lower()

    # 未登录或参数错误时服务端返回的是 JSON，不是 SSE；单独标记，别当成空回答
    if "text/event-stream" not in content_type:
        body = resp.read().decode("utf-8", "replace")
        conn.close()
        return {
            "http_status": resp.status,
            "content_type": content_type,
            "not_sse": True,
            "raw": body[:500],
            "first_delta_ms": None,
            "total_ms": round((time.perf_counter() - started) * 1000),
            "events": [],
            "answer": "",
        }

    events: list[dict] = []
    first_delta_ms = None
    current = None
    while True:
        line = resp.readline()
        if not line:
            break
        text = line.decode("utf-8", "replace").rstrip("\r\n")
        if text.startswith("event:"):
            current = text[len("event:"):].strip()
            continue
        if not text.startswith("data:"):
            continue
        payload = text[len("data:"):].strip()
        try:
            body = json.loads(payload)
        except json.JSONDecodeError:
            continue
        elapsed = (time.perf_counter() - started) * 1000
        events.append({"event": current, "ms": round(elapsed), "data": body.get("data")})
        if current == "message_delta" and first_delta_ms is None:
            first_delta_ms = elapsed
    conn.close()

    answer = "".join(
        str(item["data"].get("delta", ""))
        for item in events
        if item["event"] == "message_delta" and isinstance(item["data"], dict)
    )
    return {
        "http_status": resp.status,
        "content_type": content_type,
        "not_sse": False,
        "raw": "",
        "first_delta_ms": None if first_delta_ms is None else round(first_delta_ms),
        "total_ms": round((time.perf_counter() - started) * 1000),
        "events": events,
        "answer": answer,
    }


def summarise_events(events: list[dict]) -> dict:
    """把事件流压成工具序列、终态与错误码。"""
    tools: list[str] = []
    tool_states: list[str] = []
    thinking = 0
    errors: list[str] = []
    steps = None
    truncated = None
    done = False
    for item in events:
        name = item["event"]
        data = item["data"] if isinstance(item["data"], dict) else {}
        if name == "thinking":
            thinking += 1
            tool = data.get("tool")
            if tool:
                tools.append(str(tool))
        elif name == "tool_result":
            tool_states.append(f"{data.get('tool')}:{'ok' if data.get('success') else 'fail'}")
        elif name == "done":
            done = True
            steps = data.get("steps")
            truncated = data.get("truncated")
        elif name == "error":
            errors.append(str(data.get("code")))
        elif name == "degrade":
            errors.append("DEGRADE")
    return {
        "tools": tools,
        "tool_states": tool_states,
        "thinking_events": thinking,
        "errors": errors,
        "steps": steps,
        "truncated": truncated,
        "done": done,
    }


# --------------------------------------------------------------------------- #
# 指标
# --------------------------------------------------------------------------- #
def percentile(values: list[float], pct: float) -> float | None:
    """最近秩法分位数：样本少时比插值更诚实，不会造出样本里没有的值。"""
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, math.ceil(pct / 100 * len(ordered)) - 1)
    return round(ordered[index], 1)


def median(values: list[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    mid = len(ordered) // 2
    if len(ordered) % 2:
        return round(ordered[mid], 1)
    return round((ordered[mid - 1] + ordered[mid]) / 2, 1)


def histogram(values: list[int]) -> dict:
    out: dict[str, int] = {}
    for value in values:
        key = str(value)
        out[key] = out.get(key, 0) + 1
    return dict(sorted(out.items(), key=lambda kv: int(kv[0])))


# --------------------------------------------------------------------------- #
# 子命令：单轮评测
# --------------------------------------------------------------------------- #
def load_eval_set(path: Path, limit: int) -> list[dict]:
    cases = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            cases.append(json.loads(line))
    return cases[:limit] if limit else cases


def cmd_single(args: argparse.Namespace) -> int:
    state_root = REPO_ROOT / ".agentscope" / "state"
    log_path = (REPO_ROOT / args.log_file).resolve()

    cases = load_eval_set(Path(args.eval_set), args.limit)
    tokens: dict[str, str] = {}
    for case in cases:
        mobile = case.get("mobile") or args.mobile
        if mobile not in tokens:
            tokens[mobile] = login(args.host, args.port, mobile, args.code,
                                   args.redis_host, args.redis_port)
            print(f"[login] mobile={mobile}", flush=True)

    out_dir = Path(args.out_dir) / f"{args.arm}-{time.strftime('%Y%m%d-%H%M%S')}"
    (out_dir / "answers").mkdir(parents=True, exist_ok=True)

    # 每个参与评测的手机号各热身一次：既拿到 uid，又把冷启动成本挡在统计之外
    uids: dict[str, str] = {}
    for mobile, token in tokens.items():
        uid = discover_uid(args.host, args.port, token, log_path, args.timeout, args.log_wait)
        if not uid:
            raise SystemExit("未能从日志解析出 session，请检查 --log-file 是否指向服务端 info.log")
        uids[mobile] = uid
        print(f"[warmup] mobile={mobile} session={uid}", flush=True)

    samples: list[dict] = []

    for rep in range(args.reps):
        for case in cases:
            mobile = case.get("mobile") or args.mobile
            reset_state(state_root, uids[mobile])
            log_offset = log_path.stat().st_size if log_path.exists() else 0

            result = stream_chat(args.host, args.port, tokens[mobile], case["q"], args.timeout)
            time.sleep(args.log_wait)
            chunk, log_offset = read_log_delta(log_path, log_offset)
            models, tool_logs = collect_calls(chunk)
            event_stats = summarise_events(result["events"])

            expected = case.get("expect") or []
            forbidden = case.get("forbid") or []
            forbid_text = case.get("forbid_text") or []
            answer = result["answer"]
            record = {
                "arm": args.arm,
                "rep": rep,
                "id": case["id"],
                "cat": case["cat"],
                "q": case["q"],
                "mobile": mobile,
                "http_status": result["http_status"],
                "not_sse": result["not_sse"],
                "raw": result["raw"],
                "first_delta_ms": result["first_delta_ms"],
                "total_ms": result["total_ms"],
                "steps": event_stats["steps"],
                "truncated": event_stats["truncated"],
                "done": event_stats["done"],
                "errors": event_stats["errors"],
                "tools": event_stats["tools"],
                "tool_states": event_stats["tool_states"],
                "model_calls": models,
                "tool_logs": tool_logs,
                "token_in": sum(call["in"] for call in models if call["in"] is not None),
                "token_out": sum(call["out"] for call in models if call["out"] is not None),
                "token_cached": sum(call["cached"] for call in models if call["cached"] is not None),
                "msg_count_last": models[-1]["msgCount"] if models else None,
                "answer": answer,
                "expect_hit": all(tool in event_stats["tools"] for tool in expected),
                "forbidden_hit": any(tool in event_stats["tools"] for tool in forbidden),
                "forbid_text_hit": [text for text in forbid_text if text in answer],
            }
            if case.get("refuse"):
                record["refuse_ok"] = bool(REFUSE_RE.search(answer)) and not ACTION_CLAIM_RE.search(answer)
            samples.append(record)

            (out_dir / "answers" / f"{case['id']}-r{rep}.md").write_text(
                f"# {case['id']} {case['cat']}（rep {rep}）\n\n"
                f"- 问题：{case['q']}\n"
                f"- 工具：{event_stats['tools']}\n"
                f"- 首字：{result['first_delta_ms']}ms / 整轮：{result['total_ms']}ms / steps={event_stats['steps']}\n"
                f"- token：in={record['token_in']} out={record['token_out']} cached={record['token_cached']}\n"
                f"- 错误：{event_stats['errors']}\n\n```\n{answer}\n```\n",
                encoding="utf-8",
            )
            print(
                f"[{args.arm}] rep{rep} {case['id']:<4} tools={','.join(event_stats['tools']) or '-':<45} "
                f"first={result['first_delta_ms']}ms total={result['total_ms']}ms "
                f"steps={event_stats['steps']} in={record['token_in']}",
                flush=True,
            )

    with (out_dir / "samples.jsonl").open("w", encoding="utf-8") as handle:
        for sample in samples:
            handle.write(json.dumps(sample, ensure_ascii=False) + "\n")

    summary = build_summary(args.arm, samples)
    (out_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2),
                                          encoding="utf-8")
    print("\n" + json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"\n结果目录：{out_dir}")
    return 0


def build_summary(arm: str, samples: list[dict]) -> dict:
    """按 SOP §8 的口径聚合，缺数据的指标明确写 None，不填 0 冒充。"""
    tool_cases = [s for s in samples if s["tools"] or s["cat"].startswith("工具选择")]
    judge_tool = [s for s in samples if s["cat"].startswith("工具选择")]
    refuse_cases = [s for s in samples if "refuse_ok" in s]
    first_deltas = [s["first_delta_ms"] for s in samples if s["first_delta_ms"] is not None]
    totals = [s["total_ms"] for s in samples if s["not_sse"] is False]
    steps = [s["steps"] for s in samples if s["steps"] is not None]
    turn_tokens = [s["token_in"] + s["token_out"] for s in samples if s["token_in"]]
    errors = [s for s in samples if s["errors"] or s["not_sse"]]

    return {
        "arm": arm,
        "samples": len(samples),
        "calls": {
            "tool_selection_accuracy": _ratio(sum(1 for s in judge_tool if s["expect_hit"]), len(judge_tool)),
            "tool_selection_detail": [
                {"id": s["id"], "expect_hit": s["expect_hit"], "tools": s["tools"]}
                for s in judge_tool
            ],
            "unexpected_tool_hits": [s["id"] for s in tool_cases if s["forbidden_hit"]],
            "refuse_pass": f"{sum(1 for s in refuse_cases if s['refuse_ok'])}/{len(refuse_cases)}",
            "refuse_detail": [
                {"id": s["id"], "refuse_ok": s["refuse_ok"], "forbid_text_hit": s["forbid_text_hit"]}
                for s in refuse_cases
            ],
            "forbid_text_leaks": [s["id"] for s in samples if s["forbid_text_hit"]],
        },
        "latency_ms": {
            "first_delta_p50": median(first_deltas),
            "first_delta_p95": percentile(first_deltas, 95),
            "total_p50": median(totals),
            "total_p95": percentile(totals, 95),
        },
        "steps": {
            "p50": median(steps),
            "p95": percentile(steps, 95),
            "histogram": histogram([int(x) for x in steps]),
            "truncated": sum(1 for s in samples if s["truncated"]),
        },
        "tokens": {
            "turn_total_p50": median(turn_tokens),
            "in_p50": median([s["token_in"] for s in samples if s["token_in"]]),
            "out_p50": median([s["token_out"] for s in samples if s["token_out"]]),
            "cached_ratio_p50": median([
                round(s["token_cached"] / s["token_in"], 3)
                for s in samples if s["token_in"]
            ]),
        },
        "errors": {
            "count": len(errors),
            "rate": _ratio(len(errors), len(samples)),
            "codes": sorted({code for s in samples for code in s["errors"]}),
        },
    }


def _ratio(part: int, whole: int) -> float | None:
    if not whole:
        return None
    return round(part / whole, 3)


# --------------------------------------------------------------------------- #
# 子命令：长会话
# --------------------------------------------------------------------------- #
LONG_TURNS = [
    "你好，我想买点家居用品",
    "有纯棉的四件套吗",
    "预算大概1000以内",
    "1006002这个商品的详情帮我看下",
    "它是什么材质的",
    "和1006007那个羊毛被比哪个更值得买",
    "羊毛被现在有货吗",
    "我的待付款订单有哪些",
    "我最近一次买的东西金额是多少",
    "帮我看看我全部订单大概花在哪些类目上",
    "刚才第一个问题我问的是什么",
    "帮我推荐一个5000元以内的手机",
    "有更便宜的吗",
    "这两个哪个销量高",
    "我想开增值税发票可以吗",
    "快递一般几天到",
    "如果我不想要了怎么退款",
    "帮我看看我的订单里有没有已经完成的",
    "谢谢，那我再想想",
    "最后帮我总结一下你刚才推荐了什么",
]


def cmd_long(args: argparse.Namespace) -> int:
    state_root = REPO_ROOT / ".agentscope" / "state"
    log_path = (REPO_ROOT / args.log_file).resolve()

    token = login(args.host, args.port, args.mobile, args.code,
                  args.redis_host, args.redis_port)
    # 长会话必须从干净状态起步，否则上一轮评测留下的上下文会污染曲线
    uid = discover_uid(args.host, args.port, token, log_path, args.timeout, args.log_wait)
    if uid:
        reset_state(state_root, uid)

    out_dir = Path(args.out_dir) / f"{args.arm}-{time.strftime('%Y%m%d-%H%M%S')}"
    (out_dir / "answers").mkdir(parents=True, exist_ok=True)

    turns = LONG_TURNS[:args.turns]
    samples = []
    log_offset = log_path.stat().st_size if log_path.exists() else 0

    for index, question in enumerate(turns, start=1):
        log_offset = log_path.stat().st_size if log_path.exists() else 0
        result = stream_chat(args.host, args.port, token, question, args.timeout)
        time.sleep(args.log_wait)
        chunk, log_offset = read_log_delta(log_path, log_offset)
        models, tool_logs = collect_calls(chunk)
        stats = summarise_events(result["events"])
        record = {
            "turn": index,
            "q": question,
            "first_delta_ms": result["first_delta_ms"],
            "total_ms": result["total_ms"],
            "steps": stats["steps"],
            "tools": stats["tools"],
            "errors": stats["errors"],
            "msg_count": models[-1]["msgCount"] if models else None,
            "model_calls": len(models),
            "token_in": sum(call["in"] for call in models if call["in"] is not None),
            "token_out": sum(call["out"] for call in models if call["out"] is not None),
            "token_cached": sum(call["cached"] for call in models if call["cached"] is not None),
            "in_first_call": models[0]["in"] if models else None,
            "answer_chars": len(result["answer"]),
        }
        samples.append(record)
        (out_dir / "answers" / f"turn-{index:02d}.md").write_text(
            f"# 第 {index} 轮：{question}\n\n"
            f"- 首字 {result['first_delta_ms']}ms / 整轮 {result['total_ms']}ms / steps={stats['steps']}\n"
            f"- msgCount={record['msg_count']} in={record['token_in']} out={record['token_out']} "
            f"cached={record['token_cached']}\n"
            f"- 工具 {stats['tools']} 错误 {stats['errors']}\n\n```\n{result['answer']}\n```\n",
            encoding="utf-8",
        )
        print(
            f"[long] turn{index:02d} msgCount={record['msg_count']} in={record['token_in']} "
            f"out={record['token_out']} cached={record['token_cached']} "
            f"first={result['first_delta_ms']}ms total={result['total_ms']}ms",
            flush=True,
        )

    with (out_dir / "samples.jsonl").open("w", encoding="utf-8") as handle:
        for sample in samples:
            handle.write(json.dumps(sample, ensure_ascii=False) + "\n")

    in_series = [s["token_in"] for s in samples if s["token_in"]]
    msg_series = [s["msg_count"] for s in samples if s["msg_count"] is not None]
    summary = {
        "arm": args.arm,
        "turns": len(samples),
        "msg_count": {"first": msg_series[0] if msg_series else None,
                      "last": msg_series[-1] if msg_series else None,
                      "max": max(msg_series) if msg_series else None,
                      "series": msg_series},
        # 压缩是否生效，看这条曲线有没有拐头：单调上升且无回落 = 没有压缩
        "input_tokens": {"first": in_series[0] if in_series else None,
                         "last": in_series[-1] if in_series else None,
                         "max": max(in_series) if in_series else None,
                         "series": in_series},
        "latency_ms": {
            "first_delta_p50": median([s["first_delta_ms"] for s in samples if s["first_delta_ms"]]),
            "total_p50": median([s["total_ms"] for s in samples]),
            "total_max": max(s["total_ms"] for s in samples) if samples else None,
        },
        "errors": sorted({code for s in samples for code in s["errors"]}),
        "truncated_turns": [s["turn"] for s in samples if s["steps"] and s["steps"] >= 6],
    }
    (out_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2),
                                          encoding="utf-8")
    print("\n" + json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"\n结果目录：{out_dir}")
    return 0


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Agent 通道基线评测跑批")
    sub = parser.add_subparsers(dest="mode", required=True)

    def common(target: argparse.ArgumentParser) -> None:
        target.add_argument("--host", default="127.0.0.1")
        target.add_argument("--port", type=int, default=82)
        target.add_argument("--mobile", default="19900000004", help="登录手机号，默认用户 99")
        target.add_argument("--code", default="1234")
        target.add_argument("--redis-host", default="127.0.0.1")
        target.add_argument("--redis-port", type=int, default=6380)
        target.add_argument("--arm", required=True, help="本次跑批的配置标签")
        target.add_argument("--out-dir", default=str(Path(__file__).resolve().parent / "out"))
        target.add_argument("--log-file", required=True,
                            help="服务端日志文件（相对仓库根目录），token 数据来源")
        target.add_argument("--timeout", type=float, default=180)
        target.add_argument("--log-wait", type=float, default=0.4,
                            help="请求结束后等待日志刷盘的时间")

    single = sub.add_parser("single", help="单轮评测（每次清空会话状态）")
    common(single)
    single.add_argument("--eval-set", default=str(DEFAULT_EVAL_SET))
    single.add_argument("--reps", type=int, default=1)
    single.add_argument("--limit", type=int, default=0)
    single.set_defaults(func=cmd_single)

    long_cmd = sub.add_parser("long", help="长会话上下文增长曲线（不清状态）")
    common(long_cmd)
    long_cmd.add_argument("--turns", type=int, default=20)
    long_cmd.set_defaults(func=cmd_long)

    return parser


def main(argv: list[str]) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
