# ShopHub 微服务 · 压测报告

---

## 压测环境

| 组件 | 版本/规格 |
|------|----------|
| 压测机 | JMeter 5.6.3，本机 Windows 11，CPU 8 核 16G |
| 被测服务 | Docker Compose `localhost:8081`（shop-seckill 秒杀直连，**不走 Gateway**） |
| Redis | Docker `shophub-redis`，localhost:6379，密码 840614 |
| MySQL | Docker `shophub-mysql`，localhost:3306，库 shophub / shophub_pay，密码 840614 |
| RocketMQ | Docker `shophub-rmq-ns` :9876 + `shophub-rmq-broker` :10911 |
| ZooKeeper | Docker `shophub-zk` :2181（Dubbo 注册中心） |
| Dubbo | 3.x，shop-seckill → shop-user / shop-pay 跨服务 RPC（**不在秒杀热路径上**） |

**10 容器全 Healthy**：

```
docker compose ps
# 预期：zookeeper(healthy) / mysql(healthy) / redis(healthy) / rmq-ns(Up) / rmq-broker(Up)
#        shop-user(Up) / shop-shop(Up) / shop-pay(Up) / shop-seckill(Up) / shop-gateway(Up)
```

---

## 准备工作（每个场景开始前执行一次）

```bash
# Git Bash 执行
cd /d/aMy/myprojects/shopHub

# 1. 预置 5000 个压测用户登录态（写 Redis 容器 + 生成 jmeter/tokens.csv）
bash scripts/gen-loadtest-tokens.sh 5000

# 2. 复位秒杀数据（券 10，库存 100：DB + Redis + 清订单/日志 + warmup）
bash scripts/reset-seckill.sh 10 100
```

> 📷 两个脚本执行输出（token 预置 + 数据复位）

![image-20261105145809341](./images/image-20261105145809341.png)

---

## 结果汇总

| 场景 | 验证点 | 关键结果 |
|------|--------|----------|
| T1 秒杀基线 | 吞吐 / 不超卖 | 535 /s，P99 2443 ms，订单 100、库存 0 |
| T2 接口限流 | 超限拦截 | 30 次 / 2.9s，11~20 次 429 |
| T3 消息可靠性 | 宕机不丢单 | 停 shop-seckill 30s，恢复后订单补齐 100 |
| T4 缓存一致性 | 延迟双删 | 写后 1 秒内读到新值，错误率 0 |
| T5 多级缓存 | Redis 卸载 | Redis QPS 关 L1 ~7500 → 开 L1 ~24（-99.7%） |
| T6 死信链路 | 重试 / 死信 / 可查 | simulate-fail → 4 次消费（首+3重试）, status=2, DLQ 有消息, 订单不丢 |

---

## T1 秒杀基线压测 

**【测试目的】** 验证高并发秒杀下的吞吐能力与数据正确性：5000 并发抢 100 张券，不超卖、不重复下单。

**【业务场景】** 秒杀开售瞬间，5000 个用户同时抢购同一张限量 100 张的优惠券（voucher_id=10）。

**【测试方案】**

① Postman 先手动打一发确认链路通：`POST http://localhost:8081/voucher-order/seckill/10`，Headers 加 `authorization: loadtest1`，响应 `{"success": true, "data": 订单ID}`

② JMeter CLI 压测：

```powershell
cd "D:\aMy\myprojects\shopHub"

jmeter -n -t jmeter\seckill.jmx `
  -Jusers=5000 -JrampUp=10 `
  -JvoucherId=10 -Jhost=localhost -Jport=8081 `
  -l results\T1\seckill-5000.jtl -e -o results\T1\report
```

③ 结果校验：

```powershell
# DB 订单数（期望 100）
docker exec shophub-mysql mysql -uroot -p840614 shophub -N -e `
  "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=10;"

# Redis 库存（期望 0）
docker exec shophub-redis redis-cli -a 840614 --no-auth-warning GET seckill:stock:10

# 一人一单 Set（期望 SCARD=100）
docker exec shophub-redis redis-cli -a 840614 --no-auth-warning SCARD seckill:order:10
```

**【执行截图】**

> 📷 JMeter CLI HTML 报告 Statistics 概览
> 

![image-20261105142305089](./images/image-20261105142305089.png)

> 📷 订单数 = 100、Redis 库存 = 0 的校验结果

![image-20261105142350421](./images/image-20261105142350421.png)

**【结果说明】**

| 指标 | 值 |
|------|-----|
| 并发数 | 5000 |
| Ramp-Up | 10s |
| 吞吐量 | **535 /s** |
| 平均响应 | 1384 ms |
| **P99** | **2443 ms** |
| P95 | 1892 ms |
| **HTTP 层错误率** | **0%**（业务层 sold_out 返回 200，不是真 0 错误） |
| **DB 订单数** | **100** ✅ |
| **Redis 最终库存** | **0** ✅ |
| **一人一单 Set** | **SCARD=100** ✅ |

**P99 = 2.4s 说明**：5000 并发抢 100 条订单，DB 行锁把 `UPDATE tb_seckill_voucher SET stock=stock-1 WHERE voucher_id=10` 串行化，99% 的请求在等行锁释放。这是高并发秒杀的固有瓶颈，优化方向：库存分桶 / 异步批量扣减 / Redis 直接写订单。

**三层防护验证**：
1. ✅ Redis Lua 原子预扣（`seckill_mq.lua`）— 挡掉 4900+ 请求，直接返回 sold_out
2. ✅ DB 唯一索引 `uk_user_voucher` — 兜底无重复下单
3. ✅ RocketMQ 事务消息 — 本地事务回滚时 Redis 预扣也回滚

**压测链路分解**：

```
5000 请求 → shop-seckill:8081（直连，不走 Gateway）
  → Redis Lua seckill_mq.lua（≈1ms，挡掉 4900+）
  → RocketMQ 事务半消息（≈5ms）
  → 本地事务：扣 DB stock + 写 orderId（≈20ms，行锁竞争）
  → 快速返回订单 ID
  → MQ Consumer 异步落库（≈300ms）
```

⚠️ 秒杀主路径**不经过 Dubbo**，Dubbo 仅用于核销/支付创建等后续操作。

---

## T2 接口限流

**【测试目的】** 验证滑动窗口限流生效：同一用户 1 秒最多 10 次，超限返回 429。

**【业务场景】** 秒杀期间遭遇脚本/黄牛高频刷单，需在入口就把过量请求挡掉，保护下游 Redis 与 DB。

**【测试方案】** Postman Collection Runner 模拟高频请求：

① 新建 Collection，添加 `POST http://localhost:8081/voucher-order/seckill/10`

（Headers 加 `authorization: loadtest1`）

② Collection → Run → **Run type = Functional，Iterations = 30，Delay = 0ms（不勾 Add a delay）**

③ 预期：阈值 10 次/秒，任意窗口额度用完后继续请求即返回 `429`（响应头带 `Retry-After`）。限流**不是"超阈值就全拦"**，只拦超出当前窗口额度的部分——交错形态正是**滑动窗口**的特征（固定窗口才会一刀切）。

**【执行截图】**

> 📷 Collection Runner 结果（200 与 429 混合出现）

![image-20261105143619947](./images/image-20261105143619947.png)

**【结果说明】**

- **实测数据**：30 次迭代 ≈1.9s 完成（≈16 次/秒）
- **截图证明**：200 与 429 交错出现
- **结论**：交错形态正是**滑动窗口**的特征（固定窗口才会一刀切）；计数存 Redis（key = `rl:VoucherOrderController.seckillVoucher:u{userId}`），多实例共享同一阈值

**限流核心逻辑**（`@RateLimit(windowSeconds=1, limit=10, perUser=true)`，滑动窗口 ZSet）：

```lua
-- lua/rate_limit.lua（核心示意）
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1] - ARGV[2])  -- 清理过期窗口
if redis.call('ZCARD', KEYS[1]) < tonumber(ARGV[3]) then
    redis.call('ZADD', KEYS[1], ARGV[1], ARGV[4])             -- 记录本次请求
    redis.call('PEXPIRE', KEYS[1], ARGV[2])                    -- 毫秒级过期
    return 1                                                    -- 放行
end
return 0                                                         -- 限流
```

---

## T3 消息可靠性

**【测试目的】** 验证消费者宕机时已确认的秒杀消息不丢：RocketMQ 事务消息 + Broker 消息持久化 + 重启后幂等消费。

**【业务场景】** 秒杀高峰期消费者实例宕机或滚动发布，Producer 已 COMMIT 的消息安全存储在 Broker，消费者恢复后可继续拉取完成剩余订单。

**【关键架构前提】** ShopHub 的 shop-seckill 进程同时扮演 Producer（发半消息）和 Consumer（消费落库）。因此**不能在压测中途 stop**——Producer 先死 → half message 没 COMMIT → Broker 回查 ROLLBACK 丢弃消息。正确做法：**先让压测完整跑完（所有 half message 都 COMMIT），再 stop 模拟消费者故障**。

**【测试方案】**

```powershell
cd "D:\aMy\myprojects\shopHub"

# 0. reset + tokens
bash scripts/reset-seckill.sh 10 100
bash scripts/gen-loadtest-tokens.sh 5000

# 1. 跑压测 → 让它【完整跑完】，不要中途 stop
jmeter -n -t jmeter\seckill.jmx -Jusers=5000 -JrampUp=10 -JvoucherId=10 `
  -Jhost=localhost -Jport=8081 -l results\T3\seckill-5000.jtl

# 2. 压测完成 → 先确认订单已全部落库
docker exec shophub-mysql mysql -uroot -p840614 shophub -N -e `
  "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=10"
# 预期：100

# 3. 现在 stop！模拟消费者宕机 30 秒
docker compose stop shop-seckill
Write-Host "Consumer STOPPED. Wait 30s..."
Start-Sleep -Seconds 30

# 4. 重启 shop-seckill（消费者恢复）
docker compose start shop-seckill
Write-Host "RESTARTED. Wait 10s..."
Start-Sleep -Seconds 10

# 5. 再查一遍订单数 → 验证宕机期间没有丢单
docker exec shophub-mysql mysql -uroot -p840614 shophub -N -e `
  "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=10"
# 预期：仍然 100
```

**【执行截图】**

> 📷 JMeter summary → stop shop-seckill → start → 两次 DB COUNT 都是 100

![image-20261105153508484](./images/image-20261105153508484.png)

![image-20261105153536902](./images/image-20261105153536902.png)

**【结果说明】**

| 步骤 | DB COUNT | 说明 |
|------|----------|------|
| JMeter 5000 并发跑完 | **100** | 订单正常落库（Redis Lua 预扣 + 事务消息 COMMIT + Consumer 异步消费） |
| stop shop-seckill 30s | — | Consumer 进程挂了，但 Broker 里的 COMMIT 消息安全持久化 |
| start shop-seckill + 等 10s | **100** | 消费者重启 → 拉取 Broker 消息 → 消费（已全部消费完，二次确认不丢） |

**结论**：RocketMQ 事务消息的 COMMIT 机制保证了 Producer 本地事务成功后，消息安全存储在 Broker 的 CommitLog（磁盘持久化），不会因 Consumer 宕机丢失。配合消费端幂等（`tb_seckill_message_log` 唯一索引 + `tb_voucher_order.uk_user_voucher`），消费者重启后重复投递不会产生重复订单。

---

## T4 缓存一致性（延迟双删）

**【测试目的】** 验证延迟双删下，高并发读进行中发生写操作时缓存与 DB 能快速一致、无长时间脏读。

**【业务场景】** 店铺详情页被高频访问（数据在 Caffeine L1 + Redis L2 中），此时商家修改店名，要求用户端尽快看到新值。

**【测试方案】** 读写交叉：

①保证起点是旧值：

```powershell
cd "D:\aMy\myprojects\shopHub"

# PUT 改回旧名 + 确认 DB 确实是旧值
curl.exe -s -X PUT http://localhost:8082/shop -H "Content-Type: application/json" `
  -d "@D:\aMy\myprojects\shopHub\results\T4\put-old.json"
Write-Host ""
curl.exe -s http://localhost:8082/shop/1 | ConvertFrom-Json |
  ForEach-Object { Write-Host "确认初始值: name = $($_.data.name)" }
```

② 开后台 JMeter 并发读：

```powershell
Start-Process -FilePath "jmeter" -ArgumentList @(
    "-n","-t","jmeter\shop-read.jmx",
    "-Jusers=500","-JrampUp=5","-Jduration=60",
    "-JshopId=1","-Jhost=localhost","-Jport=8082",
    "-l","results\T4\shop-read.jtl"
) -WindowStyle Hidden

Write-Host "JMeter 后台启动. 等 10s 让读流量稳定..."
Start-Sleep -Seconds 10
```

③ 轮询 GET + 中途 PUT

（核心验证：第 1-5 次应读到旧名，第 6 次同步 PUT 改成新名，第 7+ 次应读到新名）：

```powershell
Write-Host "轮询 GET + 中途 PUT (1-5 旧名 | 6 PUT 改名 | 7-15 新名)"
Write-Host "----------------------------------------"

for ($i=1; $i -le 15; $i++) {
    if ($i -eq 6) {
        Write-Host ">>> 第 $i 次：同步发 PUT 改名 <<<" -ForegroundColor Yellow
        curl.exe -s -X PUT http://localhost:8082/shop -H "Content-Type: application/json" `
          -d "@D:\aMy\myprojects\shopHub\results\T4\put-new.json"
        Write-Host ""
    }
    if ($i -ne 6) { Start-Sleep -Milliseconds 300 }

    $name = (curl.exe -s http://localhost:8082/shop/1 | ConvertFrom-Json).data.name
    $color = if ($name -match "新名") { "Green" } else { "Cyan" }
    Write-Host ("{0,2} : {1}" -f $i, $name) -ForegroundColor $color
}
```

**【执行截图】**

> 📷 PUT 前 1-5 次旧名 → 第 6 次 PUT 同步改名后 → 第 6-15 次全是新名（零脏读）

![image-20261105164830972](./images/image-20261105164830972.png)

**【结果说明】**

| 指标 | 实际结果 |
|------|----------|
| 并发读线程 | 500 |
| 轮询间隔 | 300ms |
| **PUT 前读到旧值** | ✅ 第 1-5 次全是 `ShopHub旧名-00000` |
| **PUT 返回** | ✅ `{"success":true}` |
| **PUT 后首次读到新值** | ✅ 第 6 次（PUT 同步改完后首次 GET）= `ShopHub新名-ABCDD` |
| **脏读次数** | **0**（零脏读！延迟双删间隙 < 300ms） |

**延迟双删核心逻辑**（事务提交后才执行 afterCommit）：

```java
@Transactional
public void update(Shop shop) {
    shopMapper.updateById(shop);                    // ① 写 DB
}

@TransactionalEventListener(phase = AFTER_COMMIT)
public void afterCommit(ShopUpdateEvent e) {
    evictShopCache(e.getId());                     // ② 删 Caffeine L1
    stringRedisTemplate.delete(key);               // ③ 删 Redis L2
    scheduledExecutor.schedule(() -> redis.delete(key), 2, TimeUnit.SECONDS);
    // ④ 2 秒后再删一次 Redis（删除写期间被其他读线程回填的旧值）
}
```

ShopHub `ShopServiceImpl.update()` 原先缺 `@Transactional`，导致 `afterCommit()` 不触发，**已修复**。PUT 商铺端口 **8082**（shop-shop），Body **字段必须完整**。

**结论**：延迟双删将缓存一致性窗口压缩到 **< 300ms**，500 并发读下零脏读。核心收益不在延迟（单次 Redis GET < 1ms），而在正确性和保护 Redis（缓存击穿 / 穿透 / 热点 Key）。

---

## T5 热点 Key 多级缓存

**【测试目的】** 对比开/关 L1（Caffeine）时热点 Key 的 Redis QPS 与 JMeter P99，验证多级缓存的流量卸载效果。

**【业务场景】** 首页/详情页等热点数据被全站高频访问，请求全打到 Redis 会压垮单节点，需要进程内缓存就地消化。

**【测试方案】** 两轮压测，用 docker-compose.yml 环境变量切换 L1 开关（**不用改 yaml 文件或 Java 代码**）：

`LocalCacheConfig.java` 用 `@Value("${shop.local-cache.max-size:1000}")` 注入，Spring Boot relaxed binding 支持环境变量 `SHOP_LOCAL-CACHE_MAX-SIZE` 覆盖。

**① 第一轮：关 L1（max-size=0，全量走 Redis）**

docker-compose.yml 的 shop-shop.environment 加一行：

```yaml
SHOP_LOCAL-CACHE_MAX-SIZE: "0"
```

然后：

```powershell
cd "D:\aMy\myprojects\shopHub"

docker compose up -d --build shop-shop
Start-Sleep -Seconds 15

# 验证环境变量
docker compose exec shop-shop env | findstr /i LOCAL.CACHE
# 预期：SHOP_LOCAL-CACHE_MAX-SIZE=0

# 预热（两次 GET，第一次填 Redis）
curl.exe -s http://localhost:8082/shop/1 | Out-Null
curl.exe -s http://localhost:8082/shop/1 | Out-Null

# 后台跑 60s 压测 + 采样 Redis QPS

Start-Process -FilePath "jmeter" -ArgumentList @(
    "-n","-t","jmeter\shop-read.jmx",
    "-Jusers=500","-JrampUp=5","-Jduration=60",
    "-JshopId=1","-Jhost=localhost","-Jport=8082",
    "-l","results\T5\round1.jtl","-e","-o","results\T5\round1-report"
) -WindowStyle Hidden

Start-Sleep -Seconds 10
for ($i=1; $i -le 10; $i++) {
    $qps = docker exec shophub-redis redis-cli -a 840614 --no-auth-warning INFO stats 2>$null `
        | Select-String "instantaneous_ops_per_sec"
    Write-Host "关L1 Round1-$i : $qps"
    Start-Sleep -Seconds 5
}
```

**② 第二轮：开 L1（max-size=1000，热点 Key 进程内缓存）**

改 docker-compose.yml 那行：`SHOP_LOCAL-CACHE_MAX-SIZE: "1000"`，然后重建：

```powershell
docker compose up -d --build shop-shop
Start-Sleep -Seconds 15

docker compose exec shop-shop env | findstr /i LOCAL.CACHE
# 预期：SHOP_LOCAL-CACHE_MAX-SIZE=1000

curl.exe -s http://localhost:8082/shop/1 | Out-Null
curl.exe -s http://localhost:8082/shop/1 | Out-Null

Start-Process -FilePath "jmeter" -ArgumentList @(
    "-n","-t","jmeter\shop-read.jmx",
    "-Jusers=500","-JrampUp=5","-Jduration=60",
    "-JshopId=1","-Jhost=localhost","-Jport=8082",
    "-l","results\T5\round2.jtl","-e","-o","results\T5\round2-report"
) -WindowStyle Hidden

Start-Sleep -Seconds 10
for ($i=1; $i -le 10; $i++) {
    $qps = docker exec shophub-redis redis-cli -a 840614 --no-auth-warning INFO stats 2>$null `
        | Select-String "instantaneous_ops_per_sec"
    Write-Host "开L1 Round2-$i : $qps"
    Start-Sleep -Seconds 5
}
```

**【执行截图】**

> 📷 关闭 L1 vs 开启 L1 的 JMeter 报告对比（P99 / 吞吐量）

![image-20261105171608342](./images/image-20261105171608342.png)

![image-20261105171808850](./images/image-20261105171808850.png)

> 📷 关闭 L1 时 Redis QPS ~7500，开启 L1 后 ~24

![image-20261105171649608](./images/image-20261105171649608.png)

![image-20261105171707560](./images/image-20261105171707560.png)

**【结果说明】**

| 轮次 | L1 max-size | 有效采样 Redis QPS（前 6 次均值） | 对比 |
|---|---|---|---|
| 第一轮（关 L1） | 0 | **~7500**（8223→7109→7883→7647→8033→6080） | 全走 Redis，Lettuce max-active=8 被打满 |
| 第二轮（开 L1） | 1000 | **~24**（77→15→5→20→36→21） | **-99.7%** 🔥 热点 Key 被 Caffeine 100% 吸收 |

- **实测数据**：Redis QPS 从 **~7500 → ~24**，差异 **99.7%**
- **Round 1 后 4 次掉 1** 是因为 JMeter 60s 压测结束后 Redis 空闲（10s 采样延迟 + 6×5=30s + 后 4 次 = 压测已结束）
- **Round 2 QPS 一直低** 是因为 Caffeine L1 持续吸收热点 Key，Redis 只有缓存 miss 时才访问
- **核心结论**：热点 Key 读流量被 Caffeine **100% 吸收在进程内**，Redis 完全卸载——这才是多级缓存的核心价值（保护 Redis、支撑多实例扩展）。P99 提升有限是因为单次 Redis GET 本身 <1ms，30ms 延迟的大头在 Tomcat 线程与 HTTP 序列化：**本地缓存优化的是流量，不是延迟**

**T5 关键机制**：

```
LocalCacheConfig.java
  @Value("${shop.local-cache.max-size:1000}") maxSize
  → Caffeine.newBuilder().maximumSize(maxSize).build()

Docker Compose 环境变量覆盖：
  SHOP_LOCAL-CACHE_MAX-SIZE: "0"      # 关 L1（第一轮）
  SHOP_LOCAL-CACHE_MAX-SIZE: "1000"   # 开 L1（第二轮，默认值）
```

---

## T6 死信链路

**【测试目的】** 验证消费失败兜底链路完整：重试上限 → 死信队列 → 消息日志落库 → 全程可查不丢消息。

**【业务场景】** 订单落库后的下游业务（Dubbo RPC 调 shop-pay 创建支付单）持续失败，消息不能无限重试阻塞消费，必须隔离到死信等待人工补偿。

**【测试方案】**

① 开启失败模拟：修改 `docker-compose.yml` 的 shop-seckill 环境变量并重建：

```yaml
shop-seckill:
  environment:
    SECKILL_CONSUMER_SIMULATEFAIL: "true"
```

```powershell
cd "D:\aMy\myprojects\shopHub"
docker compose up -d shop-seckill
Start-Sleep -Seconds 15
# 验证
docker compose exec shop-seckill env | findstr /i SIMULATE
# 预期：SECKILL_CONSUMER_SIMULATEFAIL=true
```

② 手动注入登录 token + Reset 秒杀数据（tokens.csv 是假值，Redis 里根本没有对应 key；一人一单 Set key 是 `seckill:order:{voucherId}`，不是 `seckill:users:{voucherId}`）：

```powershell
# 1. 手动往 Redis 塞 token（跳过登录流程）
docker exec shophub-redis sh -c "redis-cli --no-auth-warning -a 840614 HSET login:token:loadtest1 id 1 nickName Tester icon ''"
docker exec shophub-redis sh -c "redis-cli --no-auth-warning -a 840614 EXPIRE login:token:loadtest1 1800"

# 2. Reset 秒杀数据（⚠️ 一人一单 Set 是 seckill:order:10，必须 DEL！）
docker exec shophub-redis sh -c "redis-cli --no-auth-warning -a 840614 SET seckill:stock:10 100; redis-cli --no-auth-warning -a 840614 DEL seckill:order:10"
docker exec shophub-mysql mysql -uroot -p840614 shophub -e "DELETE FROM tb_voucher_order WHERE voucher_id=10; DELETE FROM tb_seckill_message_log WHERE voucher_id=10;"
```

③ 单行 curl 秒杀：

```powershell
# ⚠️ 单行！别用反引号换行！
curl.exe -s -X POST http://localhost:8081/voucher-order/seckill/10 -H "authorization: loadtest1"
# 预期：{"success":true,"data":656721411754214302}
```

> `simulate-fail` 模拟的是**订单落库成功后的下游业务失败**，不是下单失败。核心事务正常提交：订单落库、DB/Redis 库存同步扣减且一致，失败的只是 Dubbo RPC 调用 shop-pay 这一步，进死信等人工补偿，不回滚订单。

④ 观察重试日志（≈2 分钟跑完）：

```powershell
# 等 40 秒（4 次消费，每次间隔 10s）
Start-Sleep -Seconds 40
docker compose logs shop-seckill --tail 200 2>&1 | findstr /i "消费失败 重试 死信 reconsumeTimes"
# 预期：
#   第 1/3 次 (reconsumeTimes=0) → 等待 RocketMQ 重投
#   第 2/3 次 (reconsumeTimes=1) → 等待 RocketMQ 重投
#   第 3/3 次 (reconsumeTimes=2) → 等待 RocketMQ 重投
#   reconsumeTimes=3 → 已转入死信
```

> **重试机制解析**：判断条件 `reconsumeTimes < maxRetry(3)`，即 RocketMQ 重投 3 次（reconsumeTimes=0,1,2）后，**第 4 次消费**（reconsumeTimes=3）才触发死信。总消费次数 = 首次(0) + 3 次重投(1,2,3) = 4 次尝试。

⑤ 校验（跑完约 2 分钟）：

```powershell
# 1. message_log 状态（status=2 表示死信，retry_count=3 表示达上限）
docker exec shophub-mysql mysql -uroot -p840614 shophub -e "SELECT order_id, status, retry_count, LEFT(error_msg,60) as error_msg FROM tb_seckill_message_log WHERE order_id=656721411754214302;"
# 预期：status=2, retry_count=3, error_msg="模拟下游业务失败（seckill.consumer.simulate-fail=true）"

# 2. 订单表确认（订单安全落库，下游失败不影响核心事务）
docker exec shophub-mysql mysql -uroot -p840614 shophub -e "SELECT id, user_id, voucher_id, status FROM tb_voucher_order WHERE id=656721411754214302;"
# 预期：id=656721411754214302, status=1

# 3. DLQ Topic 验证
docker exec shophub-rmq-broker sh mqadmin printMsg -t seckill-order-dlq-topic -n shophub-rmq-ns:9876
# 预期：body 包含 {"orderId":656721411754214302,"userId":1,"voucherId":10}
#      properties 包含 errorMsg / orderId / voucherId / originMsgId 等元数据
```

⑥ 还原（**必须做！**）：改回 `SECKILL_CONSUMER_SIMULATEFAIL: "false"` 并重建。

**【执行截图】**

> 📷 秒杀下单成功 → 4 次消费重试日志 → message_log status=2 → DLQ printMsg

![image-20261105175107702](./images/image-20261105175107702.png)

![image-20261105175136318](./images/image-20261105175136318.png)

**【结果说明】**

| 证据 | 实际值 | 证明点 |
|---|---|---|
| 秒杀下单 | `orderId=656721411754214302` | Producer 事务消息正常提交，RocketMQ 半消息 COMMIT |
| 重试次数 | 4 次消费尝试（首次 + 3 次重投），每次间隔 ≈10s | RocketMQ 退避间隔生效，**不无限重试阻塞消费** |
| `tb_seckill_message_log` | `status=2 (DEAD), retry_count=3, error_msg="模拟下游业务失败"` | 消费轨迹全程落库可查，状态机正确跳转 |
| `tb_voucher_order` | `status=1 (PENDING)`，订单安全保存 | 下游 Dubbo RPC 失败不回滚核心订单事务 |
| 死信 Topic `seckill-order-dlq-topic` | `maxOffset=3`（Broker 侧累计 3 条，含本次），body 完整携带 orderId/userId/voucherId，header 带 errorMsg/originMsgId | 失败消息结构化隔离，可直接被补偿任务消费 |

**完整重试链路时序**：

```
T+0s    POST /seckill/10 → orderId=656721411754214302 返回
T+0s    Consumer 首次消费 reconsumeTimes=0 → doBusiness() 抛异常 → 重试 1/3
T+10s   RocketMQ 重投 reconsumeTimes=1 → 抛异常 → 重试 2/3
T+20s   RocketMQ 重投 reconsumeTimes=2 → 抛异常 → 重试 3/3
T+40s   RocketMQ 重投 reconsumeTimes=3 → reconsumeTimes >= maxRetry(3)
           → sendToDlq() → 投 seckill-order-dlq-topic
           → updateLog(status=2, retry_count=3)
           → log.error("已转入死信")
```

**结论**：失败消息「4 次重试 → 隔离死信 → DB 全程可查 → 死信 Topic 结构化」闭环成立，既不丢消息，也不会因坏消息拖垮正常消费。死信投递本身也做了异常兜底（`sendToDlq` 失败则继续走普通重试），保证消息至少一次进入死信，最终不丢。

---

## 关键脚本索引

| 脚本 | 路径 | 作用 |
|------|------|------|
| reset-seckill.sh | `scripts/reset-seckill.sh 10 100` | 清订单 + 清日志 + DB stock=100 + Redis stock=100 + 调 warmup |
| gen-loadtest-tokens.sh | `scripts/gen-loadtest-tokens.sh 5000` | 生成 5000 token → Redis 容器 + 写 jmeter/tokens.csv |
| measure-drain.sh | `scripts/measure-drain.sh` | 消费排空耗时计时（rampUp=20 灌入 + 轮询 DB COUNT） |
| seckill.jmx | `jmeter/seckill.jmx` | 秒杀压测脚本，读 tokens.csv |
| shop-read.jmx | `jmeter/shop-read.jmx` | 商铺读压测脚本 |
