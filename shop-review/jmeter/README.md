# JMeter 压测目录

- `seckill.jmx`：秒杀接口压测计划（线程数/优惠ID/目标地址均可用 `-J` 参数覆盖）
- `tokens.csv`：由 `scripts/gen-loadtest-tokens.sh` 生成，每行一个登录态 token

## 运行步骤

```bash
# 1. 预置 5000 个压测用户（Redis 登录态 + tokens.csv）
bash scripts/gen-loadtest-tokens.sh 5000

# 2. 复位秒杀数据（库存 100）
bash scripts/reset-seckill.sh 1 100

# 3. 运行压测并生成 HTML 报告
jmeter -n -t seckill.jmx \
  -Jthreads=1000 -Jrampup=10 -JvoucherId=1 -Jhost=localhost -Jport=8081 \
  -l result.jtl -e -o report

# 4. 校验结果
#    预期：成功订单数 = 库存数，无超卖、无重复订单
mysql -uroot -p hmdp -N -e "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=1;"
```

> Windows 下请使用 Git Bash 执行脚本；JMeter 5.5+ 需在 PATH 中。
