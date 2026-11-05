#!/bin/bash
# ============================================================
# ShopHub 秒杀压测数据复位脚本
# 用法：bash scripts/reset-seckill.sh [优惠券ID] [库存数]
# 示例：bash scripts/reset-seckill.sh 10 100
# 依赖：docker compose up 的 mysql / redis 服务
# ============================================================
set -e

VOUCHER_ID=${1:-10}           # 默认秒杀券 ID 10（ShopHub 测试数据）
STOCK=${2:-100}                # 默认库存 100
MYSQL_PWD=${MYSQL_PWD:-840614}
REDIS_PWD=${REDIS_PWD:-840614}
PROJECT_DIR="$(dirname "$0")/.."

cd "${PROJECT_DIR}"

echo "==> 复位优惠券 ${VOUCHER_ID}，库存重置为 ${STOCK}"

# 1. 清空该券的历史订单 + 重置 DB 库存
docker compose exec -T mysql mysql -uroot -p"$MYSQL_PWD" shophub -e "
DELETE FROM tb_voucher_order WHERE voucher_id = ${VOUCHER_ID};
UPDATE tb_seckill_voucher SET stock = ${STOCK} WHERE voucher_id = ${VOUCHER_ID};
"

# 2. 清空该券的消费日志（对账用），避免历史日志干扰
docker compose exec -T mysql mysql -uroot -p"$MYSQL_PWD" shophub -e "
DELETE FROM tb_seckill_message_log WHERE voucher_id = ${VOUCHER_ID};
"

# 3. 重置 Redis：库存、已下单用户集合
docker compose exec -T redis redis-cli -a "$REDIS_PWD" --no-auth-warning SET  "seckill:stock:${VOUCHER_ID}" "${STOCK}"  >/dev/null
docker compose exec -T redis redis-cli -a "$REDIS_PWD" --no-auth-warning DEL  "seckill:order:${VOUCHER_ID}"               >/dev/null

echo "==> 复位完成，校验："
echo "Redis stock   : $(docker compose exec -T redis redis-cli -a "$REDIS_PWD" --no-auth-warning GET "seckill:stock:${VOUCHER_ID}")"
echo "DB    stock   : $(docker compose exec -T mysql mysql -uroot -p"$MYSQL_PWD" shophub -N -e "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ${VOUCHER_ID};")"
echo "DB    orders  : $(docker compose exec -T mysql mysql -uroot -p"$MYSQL_PWD" shophub -N -e "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ${VOUCHER_ID};")"
echo "Redis ordered : $(docker compose exec -T redis redis-cli -a "$REDIS_PWD" --no-auth-warning SCARD "seckill:order:${VOUCHER_ID}")"

echo "✅ 预热 Redis 库存到 shop-seckill："
curl.exe -s -X POST http://localhost:8081/voucher-order/warmup 2>/dev/null || \
  echo "⚠️  warmup 跳过（shop-seckill 可能还没起来）"
