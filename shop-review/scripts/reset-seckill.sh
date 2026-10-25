#!/bin/bash
# ============================================================
# 秒杀压测数据复位脚本
# 用法：bash scripts/reset-seckill.sh [优惠券ID] [库存数]
# 示例：bash scripts/reset-seckill.sh 1 100
# ============================================================
set -e

VOUCHER_ID=${1:-1}
STOCK=${2:-100}
MYSQL_PWD=${MYSQL_PWD:-840614}
REDIS_PWD=${REDIS_PWD:-840614}
MYSQL_CTR=${MYSQL_CTR:-hmdp-mysql}
REDIS_CTR=${REDIS_CTR:-hmdp-redis}

echo "==> 复位优惠券 ${VOUCHER_ID}，库存重置为 ${STOCK}"

# 1. 清空该券的历史订单 + 重置 DB 库存
docker exec "$MYSQL_CTR" mysql -uroot -p"$MYSQL_PWD" hmdp -e "
DELETE FROM tb_voucher_order WHERE voucher_id = ${VOUCHER_ID};
UPDATE tb_seckill_voucher SET stock = ${STOCK} WHERE voucher_id = ${VOUCHER_ID};
"

# 2. 清空该券的消费日志（对账用），避免历史日志干扰
docker exec "$MYSQL_CTR" mysql -uroot -p"$MYSQL_PWD" hmdp -e "
DELETE FROM tb_seckill_message_log WHERE voucher_id = ${VOUCHER_ID};
"

# 3. 重置 Redis：库存、已下单用户集合
docker exec "$REDIS_CTR" redis-cli -a "$REDIS_PWD" --no-auth-warning SET "seckill:stock:${VOUCHER_ID}" "${STOCK}" >/dev/null
docker exec "$REDIS_CTR" redis-cli -a "$REDIS_PWD" --no-auth-warning DEL "seckill:order:${VOUCHER_ID}" >/dev/null

echo "==> 复位完成，校验："
docker exec "$REDIS_CTR" redis-cli -a "$REDIS_PWD" --no-auth-warning GET "seckill:stock:${VOUCHER_ID}"
docker exec "$MYSQL_CTR" mysql -uroot -p"$MYSQL_PWD" hmdp -N -e "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ${VOUCHER_ID};"
