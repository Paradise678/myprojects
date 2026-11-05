# ShopHub JMeter 压测目录

## 目录结构

```
D:\aMy\myprojects\shopHub\
├── jmeter/
│   ├── seckill.jmx         ← 秒杀压测
│   ├── shop-read.jmx       ← 商铺读压测
│   └── tokens.csv          ← 由 scripts/gen-loadtest-tokens.sh 生成
├── scripts/
│   ├── gen-loadtest-tokens.sh   ← 批量生成 Redis 登录态 + tokens.csv
│   └── reset-seckill.sh         ← 一键复位秒杀数据（DB + Redis）
└── docs/
    └── stress.md           ← 完整压测场景说明
```

## 运行步骤（Git Bash 或 PowerShell）

```bash
cd "D:\aMy\myprojects\shopHub"

# 前置：Docker Compose 全部服务已启动
docker compose ps   # 确认 10 个容器全 Up/Healthy

# 1. 预置 5000 个压测用户 Token（写 Redis 容器 + 生成 jmeter/tokens.csv）
bash scripts/gen-loadtest-tokens.sh 5000

# 2. 复位秒杀数据（voucherId=10, stock=100，Docker Compose 服务）
bash scripts/reset-seckill.sh 10 100

# 3. 运行压测并生成 HTML 报告（PowerShell）
cd jmeter
jmeter -n -t seckill.jmx \
  -Jusers=5000 -JrampUp=10 -JvoucherId=10 -Jhost=localhost -Jport=8081 \
  -l result.jtl -e -o report
# 报告在 jmeter/report/index.html

# 4. 校验结果（DB 订单数 = 100，不超卖；Redis stock = 0）
#    PowerShell:
mysql -uroot -p840614 shophub -N -e "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=10;"
docker compose exec redis redis-cli -a 840614 --no-auth-warning GET seckill:stock:10
```

## JMX 文件参数说明

### seckill.jmx（秒杀压测）

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `-Jusers` | 5000 | 并发线程数 |
| `-JrampUp` | 10 | Ramp-Up 秒数 |
| `-JvoucherId` | **10**（已改） | 秒杀券 ID |
| `-Jhost` | localhost | 主机 |
| `-Jport` | 8081 | shop-seckill 端口 |

CSV Data Set 读 `jmeter/tokens.csv`，变量名 `token`，每个线程取一行当 `authorization` header。

### shop-read.jmx（商铺读压测）

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `-Jusers` | 500 | 并发线程数 |
| `-Jduration` | 60 | 持续秒数 |
| `-JshopId` | 1 | 商铺 ID |
| `-Jhost` | localhost | 主机 |
| `-Jport` | 8082 | shop-shop 端口 |

## 常见问题

| 报错 | 原因 | 解决 |
|------|------|------|
| `CSV Read Error: File tokens.csv does not exist` | 先跑了 JMeter，没先生成 tokens.csv | 先 `bash scripts/gen-loadtest-tokens.sh 5000` |
| `0 samples` | CSV 0 行数据（tokens.csv 空的） | 确认脚本没报错，Docker Compose Redis 是 Running |
| `HTTP 401` | token 是假的（没写进 Redis） | 脚本会验证第一个 token，如果验证失败会警告 |
| `Unknown voucher id` | voucherId 传错了 | ShopHub 默认秒杀券是 `10`，改 jmx 时别写成 `1` |
| `MySQL server has gone away` | 压测前没 reset-seckill | 先 `bash scripts/reset-seckill.sh 10 100` |

> Windows 下 `.sh` 脚本用 Git Bash 执行；JMeter `.jmx` 文件在 PowerShell 或 CMD 下都能跑（只要 `jmeter.bat` 在 PATH）。
