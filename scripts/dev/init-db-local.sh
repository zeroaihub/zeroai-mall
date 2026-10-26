#!/usr/bin/env bash
#
# 本地数据库初始化：按正确顺序把 db-init 下的 SQL 导进 zeroai-mysql。
#
# 为什么不用 MySQL 镜像自带的 /docker-entrypoint-initdb.d 自动执行：
# 那个机制按文件名字母序跑脚本，顺序是
#   inventory_stock.sql → local_message.sql → order_status_log.sql
#   → payment_flow.sql → seckill.sql → zeroai_mall_20230719.sql
# 主库被排到最后，而 seckill.sql 会往 sys_menu 插秒杀菜单，此时表还不存在，
# 直接 ERROR 1146 让 entrypoint 退出，整库一条都导不进去。
#
# 所以本地改成：容器先正常启动（不挂 db-init），再按「主库 → 增量」顺序导入。
# 增量脚本本身是 CREATE TABLE IF NOT EXISTS + NOT EXISTS 保护的 INSERT，可重复执行。
#
# 用法：bash scripts/dev/init-db-local.sh
set -euo pipefail

CONTAINER="${CONTAINER:-zeroai-mysql}"
DB="${DB:-zeroai_mall}"
ROOT_PWD="${ROOT_PWD:-@@123456Java}"

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"

FILES=(
  db-init/zeroai_mall_20230719.sql
  db-init/local_message.sql
  db-init/order_status_log.sql
  db-init/payment_flow.sql
  db-init/inventory_stock.sql
  db-init/seckill.sql
)

echo "等待 ${CONTAINER} 就绪..."
for _ in $(seq 1 60); do
  if docker exec -e MYSQL_PWD="${ROOT_PWD}" "${CONTAINER}" mysqladmin ping -uroot --silent >/dev/null 2>&1; then
    echo "MySQL 已就绪"
    break
  fi
  sleep 2
done

for f in "${FILES[@]}"; do
  printf '导入 %-42s' "${f}"
  docker exec -i -e MYSQL_PWD="${ROOT_PWD}" "${CONTAINER}" mysql -uroot "${DB}" < "${ROOT_DIR}/${f}"
  echo " 完成"
done

echo
echo "表数量：$(docker exec -e MYSQL_PWD="${ROOT_PWD}" "${CONTAINER}" \
  mysql -uroot -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${DB}';")"
