#!/bin/bash
# ============================================================
# ShopHub 压测登录态预置脚本
# 用法：bash scripts/gen-loadtest-tokens.sh [数量]
# 依赖：docker compose up 的 redis 服务（service 名: redis）
# 作用：
#   1. 在 Redis 容器批量写入 N 个登录态（绕过短信验证码流程）
#   2. 生成 jmeter/tokens.csv 供 JMeter 的 CSV Data Set 读取
# ============================================================
set -e

COUNT=${1:-5000}
REDIS_PWD=${REDIS_PWD:-840614}
PROJECT_DIR="$(dirname "$0")/.."
CSV_DIR="${PROJECT_DIR}/jmeter"
CSV_FILE="${CSV_DIR}/tokens.csv"

cd "${PROJECT_DIR}"

echo "==> 预置 ${COUNT} 个压测用户登录态到 Redis (Docker compose redis 服务)"

# 1. 批量 HSET + EXPIRE，用 --pipe 模式写入（比逐条命令快一个数量级）
{
  for i in $(seq 1 "$COUNT"); do
    echo "HSET login:token:loadtest${i} id ${i} nickName loadtest${i}"
    echo "EXPIRE login:token:loadtest${i} 7200"
  done
} | docker compose exec -T redis redis-cli -a "$REDIS_PWD" --no-auth-warning --pipe

# 2. 生成 CSV（单列，变量名 token）
mkdir -p "$CSV_DIR"
: > "$CSV_FILE"
for i in $(seq 1 "$COUNT"); do
  echo "loadtest${i}" >> "$CSV_FILE"
done

# 3. 验证第一个 token 确实在 Redis 里
VERIFY=$(docker compose exec -T redis redis-cli -a "$REDIS_PWD" --no-auth-warning GET "login:token:loadtest1" 2>/dev/null || echo "")
if [ -n "$VERIFY" ]; then
  echo "✅ 验证通过: loadtest1 已写入 Redis"
else
  echo "⚠️  验证失败：Redis 里没找到 loadtest1。确认 docker compose 是否 up，Redis 是否 healthy"
fi

echo "==> 完成："
echo "    Redis 登录态：${COUNT} 个（2 小时有效）"
echo "    JMeter CSV：${CSV_FILE}"
