#!/usr/bin/env python3
"""把商品数据从 MySQL 灌进 Elasticsearch，用于本地冒烟。

背景：goods 索引原本只在 admin 后台点「同步」时才会创建，mobile 端不建索引。
本地只起 mobile-api 时搜索接口会直接抛 index_not_found_exception，
冒烟前必须先把索引和文档准备好。

用法：
    python scripts/dev/seed-es-goods.py \
        --mysql-container mysql --mysql-pwd '@@123456Java' \
        --es http://127.0.0.1:9200 --es-user elastic --es-pwd 123456 \
        --mapping zeroai-admin-api/src/main/resources/es/index/goods.json

说明：
- ES 密码与 `es.config.password` 一致，就是 `123456`，不需要额外加引号。
  （历史坑：compose 曾写成 `- ELASTIC_PASSWORD="123456"`，列表写法下引号进了值，
  真实密码变成 `"123456"`，导致应用端 401。2026-10-25 已把 compose 和 ES 侧都纠正。）
- createTime 必须写成 ISO 格式，mapping 是 date 类型且默认使用 strict_date_optional_time。
"""
import argparse
import base64
import json
import subprocess
import urllib.error
import urllib.request


def es_call(base, auth, method, path, body=None, ctype="application/json"):
    data = body.encode("utf-8") if isinstance(body, str) else body
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Authorization", auth)
    if data is not None:
        req.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mysql-container", default="mysql")
    ap.add_argument("--mysql-user", default="root")
    ap.add_argument("--mysql-pwd", default="@@123456Java")
    ap.add_argument("--database", default="zeroai_mall")
    ap.add_argument("--es", default="http://127.0.0.1:9200")
    ap.add_argument("--es-user", default="elastic")
    ap.add_argument("--es-pwd", default="123456")
    ap.add_argument("--index", default="goods")
    ap.add_argument("--mapping", default="zeroai-admin-api/src/main/resources/es/index/goods.json")
    args = ap.parse_args()

    auth = "Basic " + base64.b64encode(f"{args.es_user}:{args.es_pwd}".encode()).decode()

    sql = (
        "select id,name,is_hot,is_new,counter_price,retail_price,virtual_sales,"
        "is_on_sale,ifnull(keywords,''),create_time "
        f"from {args.database}.shop_goods where del_flag=0"
    )
    out = subprocess.run(
        ["docker", "exec", "-e", f"MYSQL_PWD={args.mysql_pwd}", args.mysql_container,
         "mysql", "-u" + args.mysql_user, "-N", "--batch", "--default-character-set=utf8mb4", "-e", sql],
        capture_output=True, text=True, encoding="utf-8",
    )
    if out.returncode != 0:
        raise SystemExit("导出商品失败：" + out.stderr[:500])

    with open(args.mapping, encoding="utf-8") as f:
        mapping = json.load(f)

    es_call(args.es, auth, "DELETE", f"/{args.index}")
    status, resp = es_call(args.es, auth, "PUT", f"/{args.index}", json.dumps({
        "settings": {"number_of_shards": 1, "number_of_replicas": 0},
        "mappings": mapping,
    }, ensure_ascii=False))
    if status != 200:
        raise SystemExit(f"建索引失败 {status}: {resp[:500]}")

    lines = []
    for row in out.stdout.splitlines():
        parts = row.split("\t")
        if len(parts) != 10:
            continue
        gid, name, is_hot, is_new, counter_price, retail_price, virtual_sales, is_on_sale, keywords, create_time = parts
        lines.append(json.dumps({"index": {"_index": args.index, "_id": gid}}, ensure_ascii=False))
        lines.append(json.dumps({
            "id": int(gid),
            "name": name,
            "pyname": name,
            "sales": int(virtual_sales or 0),
            "isHot": is_hot == "1",
            "isNew": is_new == "1",
            "countPrice": float(counter_price or 0),
            "retailPrice": float(retail_price or 0),
            "keyword": [k.strip() for k in keywords.split(",") if k.strip()],
            "isOnSale": is_on_sale == "1",
            "createTime": create_time.replace(" ", "T") + "+08:00",
        }, ensure_ascii=False))

    payload = ("\n".join(lines) + "\n").encode("utf-8")
    status, resp = es_call(args.es, auth, "POST", f"/{args.index}/_bulk?refresh=true", payload,
                           "application/x-ndjson")
    result = json.loads(resp)
    if result.get("errors"):
        bad = [it for it in result["items"] if it["index"].get("error")][:1]
        raise SystemExit("批量写入有失败：" + json.dumps(bad, ensure_ascii=False)[:800])

    _, count = es_call(args.es, auth, "GET", f"/{args.index}/_count")
    print(f"导完成：{len(result['items'])} 条，索引当前文档数 {count[:80]}")


if __name__ == "__main__":
    main()
