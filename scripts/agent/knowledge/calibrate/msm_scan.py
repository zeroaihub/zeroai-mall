#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""msm 阶梯表达式标定：短查询严格、长查询放宽，同时看训练集与留出集。

背景：纯百分比（3<70%）会被口语词拉高分母打死长查询；纯数字（3）又打死 2 词短查询。
ES 支持 "A<X% B<Y%" 阶梯写法，可同时兼顾。
"""
import importlib.util
import json
import sys
from collections import defaultdict

spec = importlib.util.spec_from_file_location("bc", str(__import__("pathlib").Path(__file__).with_name("bm25_calibrate.py")))
bc = importlib.util.module_from_spec(spec)
sys.modules["bc"] = bc
spec.loader.exec_module(bc)

TRAIN = bc.load_cases()
HOLD = [json.loads(l) for l in open(str(__import__("pathlib").Path(__file__).with_name("holdout.jsonl")), encoding="utf-8") if l.strip()]
MAX_HITS = 3
RRF_K = 60

CANDIDATES = [
    "2<70%",
    "3<70%",
    "2<80%",
    "2<70% 4<60%",
    "2<80% 4<60%",
    "2<80% 4<55%",
    "3<80% 5<60%",
    "2<90% 4<60% 6<50%",
    "2<100% 4<60%",
    "2",
]


def vector_hits(chunks, min_score):
    """复刻 InMemoryVectorIndex.search：过滤 → 排序 → 按 sourceId 去重取 topN。"""
    kept = [c for c in chunks if c["score"] >= min_score]
    kept.sort(key=lambda c: -c["score"])
    seen = {}
    for c in kept:
        seen.setdefault(c["sourceId"], c["score"])
        if len(seen) >= MAX_HITS:
            break
    return list(seen.items())


def fuse(bm25, vec, gate):
    if not bm25 and vec and gate > 0:
        vec = [(s, sc) for s, sc in vec if sc >= gate]
    if not bm25 and not vec:
        return []
    scores = defaultdict(float)
    for r, (s, _) in enumerate(bm25, 1):
        scores[s] += 1.0 / (RRF_K + r)
    for r, (s, _) in enumerate(vec, 1):
        scores[s] += 1.0 / (RRF_K + r)
    return sorted(scores.items(), key=lambda kv: -kv[1])[:MAX_HITS]


def attach_vec(cases, vec_file):
    vecs = json.load(open(vec_file, encoding="utf-8"))["cases"]
    for c, v in zip(cases, vecs):
        c["_vec"] = [(x["sourceId"], x["score"]) for x in v["chunks"]]


def evaluate(cases, bm25_by_id, vms, gate):
    ok = in_scope = out_scope = fp = 0
    for c in cases:
        vh = vector_hits([{"sourceId": s, "score": sc} for s, sc in c["_vec"]], vms)
        fused = fuse(bm25_by_id[c["id"]], vh, gate)
        ids = [s for s, _ in fused]
        if not c["expect"]:
            out_scope += 1
            if ids:
                fp += 1
            continue
        in_scope += 1
        pos = next((i + 1 for i, s in enumerate(ids) if s in c["expect"]), 0)
        if 1 <= pos <= 3:
            ok += 1
    return ok, in_scope, fp, out_scope


def main():
    attach_vec(TRAIN, str(__import__("pathlib").Path(__file__).resolve().parents[4] / "target" / "knowledge-calibration" / "vector_scores.json"))
    attach_vec(HOLD, str(__import__("pathlib").Path(__file__).resolve().parents[4] / "target" / "knowledge-calibration" / "holdout_vectors.json"))

    print(f"{'msm':<22}{'训练hit':>10}{'训练误':>8}{'留出hit':>10}{'留出误':>8}")
    rows = []
    for msm in CANDIDATES:
        bms = 8.5
        tr = {c["id"]: bc.es_search(bc.strip_noise(c["q"]), bms, msm) for c in TRAIN}
        ho = {c["id"]: bc.es_search(bc.strip_noise(c["q"]), bms, msm) for c in HOLD}
        tr_r = evaluate(TRAIN, tr, 0.65, 0.65)
        ho_r = evaluate(HOLD, ho, 0.65, 0.65)
        print(f"{msm:<22}{tr_r[0]:>8}/{tr_r[1]}{tr_r[2]:>7}/{tr_r[3]}"
              f"{ho_r[0]:>8}/{ho_r[1]}{ho_r[2]:>7}/{ho_r[3]}")
        rows.append((tr_r[2] + ho_r[2], -(tr_r[0] + ho_r[0]), msm, tr_r, ho_r))

    print("\n=== 综合排序（误召回总和最少 → 命中总和最高）===")
    for fp, neg, msm, tr_r, ho_r in sorted(rows)[:6]:
        print(f"  msm={msm:<22} 训练 {tr_r[0]}/{tr_r[1]} 误{tr_r[2]}  |  "
              f"留出 {ho_r[0]}/{ho_r[1]} 误{ho_r[2]}")


if __name__ == "__main__":
    main()
