#!/bin/bash
# ============================================================
# 秒杀消费排空耗时测量脚本（T4 专用）
#
# 设计要点：
# 1. rampUp=20，入口 ~250/s 灌入：快到能让低线程配置堆积，又不至于打挂入口
# 2. JMeter 结束后从 Redis SCARD 读取实际下单数作为目标，
#    不受个别连接被拒（连接数 < 5000）影响
# 3. 排空耗时 = 从压测开始到 DB 订单数达到实际下单数的总时间
#
# 用法：bash scripts/measure-drain.sh
# ============================================================
set -e

VOUCHER_ID=${VOUCHER_ID:-1}
# jmeter 不在 PATH 时回退到本地安装路径
JMETER=${JMETER:-$(command -v jmeter || echo "/d/software/apache-jmeter-5.6.3/apache-jmeter-5.6.3/bin/jmeter.bat")}

# 清理上次的结果文件
rm -f r.jtl

echo "==> 开始压测（users=5000, rampUp=20）"

# 记录开始时间
START=$(date +%s)

# 后台跑 JMeter
"$JMETER" -n -t jmeter/seckill.jmx -Jusers=5000 -JrampUp=20 \
  -JvoucherId="$VOUCHER_ID" -Jhost=localhost -Jport=8081 \
  -l r.jtl >/dev/null 2>&1 &

JMETER_PID=$!

TARGET=""
while true; do
  # JMeter 结束后，读取 Redis 实际下单数作为排空目标
  if [ -z "$TARGET" ] && ! kill -0 $JMETER_PID 2>/dev/null; then
    TARGET=$(docker compose exec -T redis redis-cli -a 840614 --no-auth-warning \
      SCARD "seckill:order:${VOUCHER_ID}" | tr -d '[:space:]')
    echo "==> JMeter 已结束，实际下单数（目标）：${TARGET}"
  fi

  CNT=$(docker compose exec -T mysql mysql -uroot -p840614 hmdp -N -e \
    "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=${VOUCHER_ID};" 2>/dev/null)
  NOW=$(date +%s)
  ELAPSED=$((NOW - START))
  echo "[$(date +%H:%M:%S)] 已耗时 ${ELAPSED}s，订单数：${CNT}"

  if [ -n "$TARGET" ] && [ -n "$CNT" ] && [ "$CNT" -ge "$TARGET" ]; then
    echo "=========================================="
    echo "✅ 排空完成！总耗时：$((NOW - START)) 秒"
    echo "=========================================="
    break
  fi
  sleep 2
done

wait $JMETER_PID 2>/dev/null || true
