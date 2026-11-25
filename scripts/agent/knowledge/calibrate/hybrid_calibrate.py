#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""hybrid 阈值标定：真实 ES(BM25) + 真实 bge-m3(向量) + 生产同款 RRF。

复用 bm25_calibrate.py 的 ES 查询与归一化逻辑，完全复刻 KnowledgeSearchRouter.searchHybrid：
  1) 两路各取 top-N（生产 max-hits=3）
  2) 向量：先按 min-score 过滤块 → 排序 → 按 sourceId 去重
  3) BM25 空且向量非空时，向量需过 vector-only-min-score 门禁
  4) RRF 融合：1/(60+rank)，同 sourceId 相加
"""
import importlib.util
import json
import sys
from collections import defaultdict

spec = importlib.util.spec_from_file_location("bc", str(__import__("pathlib").Path(__file__).with_name("bm25_calibrate.py")))
bc = importlib.util.module_from_spec(spec)
sys.modules["bc"] = bc
spec.loader.exec_module(bc)

VEC = json.load(open(str(__import__("pathlib").Path(__file__).resolve().parents[4] / "target" / "knowledge-calibration" / "vector_scores.json"), encoding="utf-8"))
CASES = bc.load_cases()
RRF_K = 60
MAX_HITS = 3


def vector_hits(case_scores, min_score):
    """复刻 InMemoryVectorIndex.search：过滤 → 排序 → 按 sourceId 去重取 topN。"""
    kept = [c for c in case_scores["chunks"] if c["score"] >= min_score]
    kept.sort(key=lambda c: -c["score"])
    seen = {}
    for c in kept:
        seen.setdefault(c["sourceId"], c["score"])
        if len(seen) >= MAX_HITS:
            break
    return list(seen.items())


def rrf(lexical, semantic):
    """复刻 KnowledgeSearchRouter.fuse。"""
    scores = defaultdict(float)
    for rank, (sid, _) in enumerate(lexical, start=1):
        scores[sid] += 1.0 / (RRF_K + rank)
    for rank, (sid, _) in enumerate(semantic, start=1):
        scores[sid] += 1.0 / (RRF_K + rank)
    return sorted(scores.items(), key=lambda kv: -kv[1])[:MAX_HITS]


def hybrid(bm25_hits, vec_hits, gate):
    if not bm25_hits and vec_hits:
        if gate > 0:
            vec_hits = [(s, sc) for s, sc in vec_hits if sc >= gate]
    if not bm25_hits and not vec_hits:
        return []
    return rrf(bm25_hits, vec_hits)


def bm25_cache(msm, min_score):
    """BM25 结果只依赖 (msm, min_score)，缓存避免重复打 ES。"""
    key = (msm, min_score)
    if key not in _CACHE:
        out = {}
        for c in CASES:
            out[c["id"]] = bc.es_search(bc.strip_noise(c["q"]), min_score, msm)
        _CACHE[key] = out
    return _CACHE[key]


_CACHE = {}


def evaluate(msm, bm25_min, vec_min, gate):
    bm = bm25_cache(msm, bm25_min)
    hit1 = hit3 = answered = 0
    in_scope = out_scope = fp = 0
    fps, misses = [], []
    for c, vs in zip(CASES, VEC["cases"]):
        vh = vector_hits(vs, vec_min)
        fused = hybrid(bm[c["id"]], vh, gate)
        ids = [s for s, _ in fused]
        if not c["expect"]:
            out_scope += 1
            if ids:
                fp += 1
                fps.append((c["id"], c["q"], ids[:1]))
            continue
        in_scope += 1
        if ids:
            answered += 1
        pos = next((i + 1 for i, s in enumerate(ids) if s in c["expect"]), 0)
        if pos == 1:
            hit1 += 1
        if 1 <= pos <= 3:
            hit3 += 1
        else:
            misses.append((c["id"], c["q"]))
    return {
        "hit1": 100.0 * hit1 / in_scope,
        "hit3": 100.0 * hit3 / in_scope,
        "answered": 100.0 * answered / in_scope,
        "fp": fp,
        "out": out_scope,
        "fps": fps,
        "misses": misses,
    }


def main():
    print(f"用例 {len(CASES)} 条；块 {VEC['chunks']}")

    # 一、先看关键库外样本在向量侧的最高分，判断门禁可行域
    print("\n=== 库外样本的向量最高分（去重前） ===")
    for c, vs in zip(CASES, VEC["cases"]):
        if c["expect"]:
            continue
        top = vs["chunks"][0]
        print(f"  {c['id']} {c['q']:<18} top1={top['score']:.3f} ({top['sourceId']})")

    print("\n=== hybrid 网格（只列零误召回或误召回≤1 的组合）===")
    print(f"{'msm':<8}{'bms':>6}{'vms':>7}{'gate':>7}{'hit@1':>8}{'hit@3':>8}{'有结果':>8}{'误召回':>9}")
    rows = []
    for msm in ["2<70%", "3<70%", "2", "3"]:
        for bms in [1.5, 3.0, 5.0, 8.0]:
            for vms in [0.58, 0.60, 0.62, 0.64, 0.65]:
                for gate in [0.0, 0.60, 0.64, 0.66, 0.68]:
                    m = evaluate(msm, bms, vms, gate)
                    if m["fp"] <= 1:
                        rows.append((m["fp"], -m["hit3"], msm, bms, vms, gate, m))
                        print(f"{msm:<8}{bms:>6.1f}{vms:>7.2f}{gate:>7.2f}"
                              f"{m['hit1']:>7.1f}%{m['hit3']:>7.1f}%{m['answered']:>7.1f}%"
                              f"{m['fp']:>6}/{m['out']}")

    print("\n=== 按「零误召回优先、hit@3 最高」排序前 10 ===")
    for fp, neg, msm, bms, vms, gate, m in sorted(rows)[:10]:
        print(f"  msm={msm:<7} bm25_min={bms:<4} vec_min={vms:<5} gate={gate:<5} "
              f"hit@3={m['hit3']:.1f}% 有结果={m['answered']:.1f}% 误召回={fp}/{m['out']}")

    # 二、最优候选的逐条明细
    best = sorted(rows)[0]
    fp, neg, msm, bms, vms, gate, m = best
    print(f"\n=== 最优候选明细 msm={msm} bm25_min={bms} vec_min={vms} gate={gate} ===")
    print(f"误召回 {m['fp']}: {m['fps']}")
    print(f"漏召回 {len(m['misses'])}: {m['misses']}")


if __name__ == "__main__":
    main()
