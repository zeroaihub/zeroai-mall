#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""BM25 阈值标定：扫描 min-score × min-should-match，输出 hit 与误召回。

只读 ES。复刻 EsKnowledgeStore.search 的 DSL 与 KnowledgeQueryNormalizer.stripNoise。
"""
import base64
import json
import os
import urllib.request

ES = os.environ.get("ES_URL", "http://127.0.0.1:9200")
INDEX = os.environ.get("ES_INDEX", "zeroai_ai_kb")
_USER = os.environ.get("ES_USER", "elastic")
_PASS = os.environ.get("ES_PASSWORD", "123456")
AUTH = base64.b64encode(f"{_USER}:{_PASS}".encode()).decode()
CASES = os.environ.get("KB_CASES", "scripts/agent/knowledge/cases-aftersale.jsonl")

NOISE = [
    "想问一下", "请问一下", "帮我看下", "帮我看看", "能不能", "可不可以", "有没有",
    "在哪里", "请问", "想问", "麻烦", "帮我", "一下",
    "怎么办", "怎么", "怎样", "怎么样", "如何", "什么", "哪些", "哪个", "哪里", "在哪",
    "可以", "能否", "你们", "我们", "这个", "那个", "现在",
    "吗", "呢", "吧", "啊", "呀", "嘛", "的", "是", "了", "我", "你",
]


def strip_noise(q, max_chars=200):
    q = q.replace("\u200b", "").replace("\u200c", "").replace("\u200d", "") \
         .replace("\u200e", "").replace("\u200f", "").replace("\ufeff", "")
    c = " ".join(q.split())[:max_chars]
    if not c:
        return ""
    s = c
    for n in NOISE:
        s = s.replace(n, " ")
    s = " ".join(s.split())
    return (s or c)[:max_chars]


def es_search(query, min_score, msm, size=3):
    body = {
        "query": {"bool": {
            "must": [{"multi_match": {
                "query": query,
                "fields": ["title^5.0", "tags^3.0", "content^1.0"],
                "type": "best_fields",
                "minimum_should_match": msm,
            }}],
            "filter": [{"term": {"visibility": "public"}}],
        }},
        "size": size,
        "min_score": min_score,
        "_source": ["sourceId"],
    }
    req = urllib.request.Request(
        f"{ES}/{INDEX}/_search",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "Authorization": "Basic " + AUTH},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = json.loads(resp.read())
    return [(h["_source"]["sourceId"], h["_score"]) for h in data["hits"]["hits"]]


def load_cases():
    return [json.loads(l) for l in open(CASES, encoding="utf-8") if l.strip()]


def evaluate(cases, min_score, msm):
    hit1 = hit3 = answered = 0
    in_scope = out_scope = fp = 0
    misses = []
    for c in cases:
        q = strip_noise(c["q"])
        hits = es_search(q, min_score, msm)
        ids = [h[0] for h in hits]
        if not c["expect"]:
            out_scope += 1
            if ids:
                fp += 1
                misses.append(("FP", c["q"], ids[:1], hits[0][1]))
            continue
        in_scope += 1
        if ids:
            answered += 1
        pos = next((i + 1 for i, sid in enumerate(ids) if sid in c["expect"]), 0)
        if pos == 1:
            hit1 += 1
        if 1 <= pos <= 3:
            hit3 += 1
        else:
            misses.append(("MISS", c["q"], c["expect"], hits[:1]))
    return {
        "hit1": 100.0 * hit1 / in_scope,
        "hit3": 100.0 * hit3 / in_scope,
        "answered": 100.0 * answered / in_scope,
        "fp": fp,
        "out": out_scope,
        "misses": misses,
    }


def main():
    cases = load_cases()
    print(f"用例 {len(cases)} 条（可答 {sum(1 for c in cases if c['expect'])}）\n")

    # 一、先看默认参数下的分数分布，为 min_score 网格定范围
    print("=== 默认参数（msm=2<70%, min_score=0）下的首条命中分 ===")
    in_scores, out_scores = [], []
    for c in cases:
        hits = es_search(strip_noise(c["q"]), 0.0, "2<70%")
        top = hits[0][1] if hits else 0.0
        (in_scores if c["expect"] else out_scores).append(top)
    in_scores.sort()
    out_scores.sort()
    pct = lambda a, p: a[min(len(a) - 1, int(len(a) * p))]
    print(f"可答 top1 分：min={in_scores[0]:.2f} p25={pct(in_scores,.25):.2f} "
          f"中位={pct(in_scores,.5):.2f} p75={pct(in_scores,.75):.2f} max={in_scores[-1]:.2f}")
    print(f"库外 top1 分：min={out_scores[0]:.2f} 中位={pct(out_scores,.5):.2f} max={out_scores[-1]:.2f}")
    print(f"可答零命中：{sum(1 for s in in_scores if s == 0)}/{len(in_scores)}")

    # 二、网格扫描
    print("\n=== 网格扫描（msm × min_score） ===")
    print(f"{'msm':<10}{'min_score':>10}{'hit@1':>8}{'hit@3':>8}{'有结果':>8}{'库外误召回':>12}")
    best = []
    for msm in ["2<70%", "2<75%", "2<80%", "2<90%", "3<70%", "3<80%", "100%", "2", "3"]:
        for min_score in [1.5, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0, 6.0, 8.0]:
            m = evaluate(cases, min_score, msm)
            print(f"{msm:<10}{min_score:>10.1f}{m['hit1']:>7.1f}%{m['hit3']:>7.1f}%"
                  f"{m['answered']:>7.1f}%{m['fp']:>8}/{m['out']}")
            best.append((m["fp"], -m["hit3"], msm, min_score, m))
    print("\n=== 按「误召回最少、hit@3 最高」排序前 8 ===")
    for fp, neg_h3, msm, ms, m in sorted(best)[:8]:
        print(f"  msm={msm:<8} min_score={ms:<5} hit@3={m['hit3']:.1f}% "
              f"hit@1={m['hit1']:.1f}% 有结果={m['answered']:.1f}% 误召回={fp}/{m['out']}")


if __name__ == "__main__":
    main()
