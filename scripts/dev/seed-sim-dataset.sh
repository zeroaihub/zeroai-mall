#!/usr/bin/env bash
#
# 仿真数据集一键造数：MySQL（商品/SKU/秒杀活动） + Redis（秒杀库存预热） + ES（搜索索引重建）
#
# 为什么三件事必须一起做（少一件链路就跑不通）：
#   1. MySQL   —— 提供商品、SKU、秒杀活动，是所有业务接口的数据源
#   2. Redis   —— 秒杀库存不走 MySQL，submit 时由 Lua 直接 DECRBY Redis。
#                 不预热会返回 -2「秒杀库存未预热」，链路在第一跳就断
#   3. ES      —— 搜索接口只查 ES，不查 MySQL。不重建索引时 keyword=SIM 搜不到任何商品，
#                 仿真用户的「搜索 → 详情」入口就是空的
#
# 用法：
#   bash scripts/dev/seed-sim-dataset.sh                # 全量造数（可重复执行）
#   SKIP_ES=1 bash scripts/dev/seed-sim-dataset.sh      # 只造 MySQL + Redis
#   ES_RESET=0 bash scripts/dev/seed-sim-dataset.sh     # 增量写 ES 文档，不删索引
set -euo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-zeroai-mysql}"
REDIS_CONTAINER="${REDIS_CONTAINER:-zeroai-redis}"
DB="${DB:-zeroai_mall}"
ROOT_PWD="${ROOT_PWD:-@@123456Java}"
ES_URL="${ES_URL:-http://127.0.0.1:9200}"
ES_USER="${ES_USER:-elastic}"
ES_PWD="${ES_PWD:-123456}"
SIM_ACTIVITY_ID="${SIM_ACTIVITY_ID:-990001}"

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
cd "${ROOT_DIR}"

echo "==> 1/3 导入仿真商品与活动（MySQL）"
docker exec -i -e MYSQL_PWD="${ROOT_PWD}" "${MYSQL_CONTAINER}" \
  mysql -uroot --default-character-set=utf8mb4 "${DB}" < scripts/dev/seed-sim-dataset.sql

echo
echo "==> 2/3 预热秒杀库存（Redis）"
# 读取 DB 里声明的 available_stock，保证 Redis 与 MySQL 起始值一致；
# 不一致时压测结束的库存恒等式（available+locked+sold=初始）必然失败。
SKU_ROWS="$(docker exec -e MYSQL_PWD="${ROOT_PWD}" "${MYSQL_CONTAINER}" \
  mysql -uroot -N -B -e \
  "SELECT id, available_stock FROM ${DB}.shop_seckill_sku WHERE activity_id=${SIM_ACTIVITY_ID} AND del_flag=0;" 2>/dev/null)"

if [ -z "${SKU_ROWS}" ]; then
  echo "    [FAIL] 没有查到 activity_id=${SIM_ACTIVITY_ID} 的秒杀 SKU" >&2
  exit 1
fi

while IFS=$'\t' read -r sku_id stock; do
  [ -z "${sku_id}" ] && continue
  stock_key="zeroai:seckill:stock:${sku_id}"
  bucket_sku_key="zeroai:seckill:bucket:sku:${sku_id}"
  # 值必须是裸整数：Lua 里 tonumber(redis.call('get', KEYS[1])) 直接读原始字节，
  # 写成 JSON（如 "1000" 带引号）会让 tonumber 得到 nil，抢占永远失败。
  docker exec "${REDIS_CONTAINER}" redis-cli SET "${stock_key}" "${stock}" >/dev/null
  # 令牌桶残留会让新一轮压测在第一秒就被限流（capacity=200/refill=200 每秒），必须清掉
  docker exec "${REDIS_CONTAINER}" redis-cli DEL "${bucket_sku_key}" >/dev/null
  actual="$(docker exec "${REDIS_CONTAINER}" redis-cli --raw GET "${stock_key}")"
  echo "    ${stock_key} = ${actual}  (DB 声明 ${stock})"
done <<< "${SKU_ROWS}"

# 用户级重复购买标记按 activity:goods:user 维度散列，无法精确枚举，按活动前缀清理。
# 只在造数时执行：仿真活动 990001 的 key 全部属于压测，不含真实数据。
cleared="$(docker exec "${REDIS_CONTAINER}" redis-cli --scan \
  --pattern "zeroai:seckill:*:990001:*" 2>/dev/null | wc -l | tr -d ' ')"
if [ "${cleared}" != "0" ]; then
  docker exec "${REDIS_CONTAINER}" redis-cli --scan \
    --pattern "zeroai:seckill:*:990001:*" 2>/dev/null \
    | while read -r k; do docker exec "${REDIS_CONTAINER}" redis-cli DEL "${k}" >/dev/null; done
  echo "    清理活动级 Redis 残留 key: ${cleared} 个"
fi

if [ "${SKIP_ES:-0}" = "1" ]; then
  echo
  echo "==> 3/3 跳过 ES 索引（SKIP_ES=1）"
else
  echo
  echo "==> 3/3 重建 ES 搜索索引"
  # seed-es-goods.py 默认会 DELETE 整个 goods 索引后按 DB 全量重建，
  # 所以仿真商品入库后必须重跑，否则搜索里看不到它们。
  python3 scripts/dev/seed-es-goods.py \
    --mysql-container "${MYSQL_CONTAINER}" \
    --mysql-pwd "${ROOT_PWD}" \
    --database "${DB}" \
    --es "${ES_URL}" \
    --es-user "${ES_USER}" \
    --es-pwd "${ES_PWD}" \
    --mapping zeroai-admin-api/src/main/resources/es/index/goods.json
fi

echo
echo "==> 完成。仿真数据口径："
echo "    商品   id 9900001~9900003  goods_sn SIM-0001~0003"
echo "    秒杀   活动 990001 / SKU 9900001 / 库存 ${stock:-?}"
echo "    验证   curl -s '${ES_URL}/goods/_count?q=name:SIM' -u ${ES_USER}:${ES_PWD}"
