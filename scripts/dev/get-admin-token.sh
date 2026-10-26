#!/usr/bin/env bash
#
# 获取后台 admin token（供 simulator 走「发货 → 确认收货」链路使用）。
#
# 为什么要这个脚本：admin 的 /login 强制图形验证码，自动化脚本拿不到。
# 但验证码的实现是「明文存 Redis + 30 分钟过期」（LoginController#captcha），
# 本地开发可以直接先申请一个 key，再从 Redis 把答案读出来回填，走的仍是真实登录链路
# （校验分支、密码 bcrypt、token 签发全都真实执行），只是绕开了「人眼识图」这一步。
#
# ⚠️ 仅限本地 / 测试环境。生产环境 Redis 通常有 ACL 且验证码 key 不可读，不要照搬。
#
# 用法：
#   bash scripts/dev/get-admin-token.sh                 # 打印 token
#   ADMIN_PASSWORD=xxx bash scripts/dev/get-admin-token.sh
#   TOKEN=$(bash scripts/dev/get-admin-token.sh)        # 在脚本里取用
set -euo pipefail

ADMIN_URL="${ADMIN_URL:-http://127.0.0.1:81}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-123456}"
REDIS_CONTAINER="${REDIS_CONTAINER:-zeroai-redis}"

captcha_json="$(curl -s -m 5 "${ADMIN_URL}/captcha")"
key="$(printf '%s' "${captcha_json}" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["key"])')"
if [ -z "${key}" ]; then
  echo "获取验证码失败: ${captcha_json}" >&2
  exit 1
fi

# RedisTemplate 的 value 序列化器是 GenericFastJsonRedisSerializer，存进去的是带双引号的 JSON 字符串
code="$(docker exec "${REDIS_CONTAINER}" redis-cli --raw GET "${key}" | tr -d '"' | tr -d '\n\r ')"
if [ -z "${code}" ]; then
  echo "从 Redis 读取验证码失败（key=${key}）" >&2
  exit 1
fi

resp="$(curl -s -m 10 -X POST "${ADMIN_URL}/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${ADMIN_USER}\",\"password\":\"${ADMIN_PASSWORD}\",\"code\":\"${code}\",\"key\":\"${key}\"}")"

token="$(printf '%s' "${resp}" | python3 -c '
import sys, json
try:
    body = json.load(sys.stdin)
except Exception:
    print("", end="")
    sys.exit(0)
if body.get("code") == 200 and body.get("data"):
    print(body["data"], end="")
' )"

if [ -z "${token}" ]; then
  echo "后台登录失败: ${resp}" >&2
  exit 1
fi

printf '%s' "${token}"
