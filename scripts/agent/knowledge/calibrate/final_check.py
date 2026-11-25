#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""最终参数的平台确认：msm=2<100% 4<60% 下扫 bm25_min 与 vec_min。"""
import importlib.util
import json
import sys

spec = importlib.util.spec_from_file_location("ms", str(__import__("pathlib").Path(__file__).with_name("msm_scan.py")))
ms = importlib.util.module_from_spec(spec)
sys.modules["ms"] = ms
spec.loader.exec_module(ms)

MSM = "2<100% 4<60%"


def main():
    ms.attach_vec(ms.TRAIN, str(__import__("pathlib").Path(__file__).resolve().parents[4] / "target" / "knowledge-calibration" / "vector_scores.json"))
    ms.attach_vec(ms.HOLD, str(__import__("pathlib").Path(__file__).resolve().parents[4] / "target" / "knowledge-calibration" / "holdout_vectors.json"))

    print(f"=== bm25_min 扫描（msm={MSM}, vec=0.65, gate=0.65）===")
    print(f"{'bms':>6}{'训练hit':>10}{'训练误':>8}{'留出hit':>10}{'留出误':>8}")
    for bms in [5, 6, 7, 7.5, 8, 8.5, 9, 9.5, 10, 12]:
        tr = {c["id"]: ms.bc.es_search(ms.bc.strip_noise(c["q"]), bms, MSM) for c in ms.TRAIN}
        ho = {c["id"]: ms.bc.es_search(ms.bc.strip_noise(c["q"]), bms, MSM) for c in ms.HOLD}
        a = ms.evaluate(ms.TRAIN, tr, 0.65, 0.65)
        b = ms.evaluate(ms.HOLD, ho, 0.65, 0.65)
        print(f"{bms:>6.1f}{a[0]:>8}/{a[1]}{a[2]:>7}/{a[3]}{b[0]:>8}/{b[1]}{b[2]:>7}/{b[3]}")

    print(f"\n=== vec_min 扫描（msm={MSM}, bm25_min=8.5, gate 同步）===")
    print(f"{'vms':>6}{'训练hit':>10}{'训练误':>8}{'留出hit':>10}{'留出误':>8}")
    tr = {c["id"]: ms.bc.es_search(ms.bc.strip_noise(c["q"]), 8.5, MSM) for c in ms.TRAIN}
    ho = {c["id"]: ms.bc.es_search(ms.bc.strip_noise(c["q"]), 8.5, MSM) for c in ms.HOLD}
    for vms in [0.58, 0.60, 0.62, 0.64, 0.65, 0.66]:
        a = ms.evaluate(ms.TRAIN, tr, vms, vms)
        b = ms.evaluate(ms.HOLD, ho, vms, vms)
        print(f"{vms:>6.2f}{a[0]:>8}/{a[1]}{a[2]:>7}/{a[3]}{b[0]:>8}/{b[1]}{b[2]:>7}/{b[3]}")

    print(f"\n=== 最终参数逐条明细（msm={MSM}, bm25=8.5, vec=0.65, gate=0.65）===")
    for name, cases, bm in [("训练", ms.TRAIN, tr), ("留出", ms.HOLD, ho)]:
        print(f"\n[{name}]")
        for c in cases:
            vh = ms.vector_hits([{"sourceId": s, "score": sc} for s, sc in c["_vec"]], 0.65)
            fused = ms.fuse(bm[c["id"]], vh, 0.65)
            ids = [s for s, _ in fused]
            if not c["expect"]:
                print(f"  {'误召回' if ids else 'ok':>6} {c['id']} {c['q']:<20} {ids[:1]}")
                continue
            pos = next((i + 1 for i, s in enumerate(ids) if s in c["expect"]), 0)
            print(f"  {'ok' if 1 <= pos <= 3 else 'MISS':>6} {c['id']} {c['q']:<20} -> {ids[:1]}")


if __name__ == "__main__":
    main()
