# 部署文档

ShopHub 微服务支持两种部署模式：**Docker Compose（主力，推荐）** 和 **IDEA 本地 Run（开发调试）**。所有压测（T1-T6）均基于 Docker Compose 完成。

---

## 环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| Docker Desktop | 4.x+ | **必需**，Windows 上勾选 WSL2 backend |
| JDK | 17+ | Oracle / OpenJDK 均可（仅 IDEA 模式需要） |
| Maven | 3.9.x | 3.6+ 也能跑（仅 IDEA 模式需要） |
| MySQL | 8.0+ | Docker Compose 内置，端口 3306 |
| Redis | 7.x | Docker Compose 内置（redis:7-alpine），端口 6379，密码 840614 |
| ZooKeeper | 3.9 | Docker Compose 内置，端口 2181 |
| RocketMQ | 4.9.7 | Docker Compose 内置（rmq-ns:9876 + rmq-broker:10911） |

> Docker Compose 模式下，**不需要**单独安装 MySQL / Redis / ZK / RMQ / Maven，全部由容器提供。只需 Docker Desktop 4.x + WSL2 + 本机 `mvn package` 预编译一次。

---

## Docker Compose

一键启动 **10 个容器**：4 中间件 + 5 业务服务 + 1 Gateway。

### 第一步：预编译（本机执行一次）

Docker 容器访问不了 Maven 仓库，所以**必须本机先 `mvn package`**，Dockerfile 只做 `COPY jar`。

```powershell
cd D:\aMy\myprojects\shopHub
mvn clean install -DskipTests
```

### 第二步：一键启动

```powershell
cd D:\aMy\myprojects\shopHub

# 首次启动（构建 5 个业务镜像）
docker compose up -d --build

# 后续增量（只启动，不 build）
docker compose up -d
```

**10 个容器 + 端口映射**：

| 容器 | 镜像 | 端口 | 作用 |
|------|------|------|------|
| shophub-zk | zookeeper:3.9 | 2181 | Dubbo 注册中心 |
| shophub-rmq-ns | apache/rocketmq:4.9.7 | 9876 | RocketMQ NameServer |
| shophub-rmq-broker | apache/rocketmq:4.9.7 | 10911, 10909 | RocketMQ Broker |
| shophub-redis | redis:7-alpine | 6379 | 缓存 + Session + 限流计数器 |
| shophub-mysql | shophub-mysql:8.0 | 3306 | 两库：shophub + shophub_pay |
| shophub-shop-user | shophub-shop-user | 8083 | 用户服务（登录/验证码） |
| shophub-shop-shop | shophub-shop-shop | 8082 | 商铺服务（CRUD + L1/L2 缓存） |
| shophub-shop-seckill | shophub-shop-seckill | **8081** | 秒杀服务（压测直连端口） |
| shophub-shop-pay | shophub-shop-pay | 8084 | 支付服务（Outbox + 对账） |
| shophub-gateway | shophub-shop-gateway | 8080 | HTTP 路由层（秒杀压测绕过） |

### 第三步：验证

```powershell
cd D:\aMy\myprojects\shopHub

# 1. 10 容器全 Healthy
docker compose ps

# 2. MySQL 库已初始化（Dockerfile.mysql 自动导入 init.sql）
docker exec shophub-mysql mysql -uroot -p840614 -e "SHOW DATABASES;"
# → shophub, shophub_pay

# 3. Redis 通
docker exec shophub-redis redis-cli -a 840614 --no-auth-warning ping
# → PONG

# 4. 秒杀端点通（先手动塞个 token）
docker exec shophub-redis sh -c "redis-cli --no-auth-warning -a 840614 HSET login:token:loadtest1 id 1 nickName Tester icon ''"
curl.exe -s -X POST http://localhost:8081/voucher-order/seckill/10 -H "authorization: loadtest1"
# → {"success":false,"errorMsg":"库存不足"} （正常，还没 warmup）
```

### 第四步：环境变量开关（无需改 YAML）

docker-compose.yml 中已配置的可热切换变量：

| 变量 | 服务 | 默认值 | 作用 |
|------|------|--------|------|
| `RATE_LIMIT_ENABLED` | shop-seckill | `false` | 滑动窗口限流总开关 |
| `SECKILL_CONSUMER_SIMULATEFAIL` | shop-seckill | `false` | **只在 T6 死信测试设 `true`** |
| `SHOP_LOCAL-CACHE_MAX-SIZE` | shop-shop | `1000` | T5 多级缓存：`0`=关 L1，`1000`=开 L1 |

切换示例：

```powershell
# T6 死信测试：开失败模拟
# docker-compose.yml → SECKILL_CONSUMER_SIMULATEFAIL: "true"
docker compose up -d shop-seckill

# T5 Round1：关 L1
# docker-compose.yml → SHOP_LOCAL-CACHE_MAX-SIZE: "0"
docker compose up -d shop-shop
```

> ⚠️ 改完环境变量必须 `docker compose up -d <service>` 重建容器（不需要 `--build`，因为只改 env）。

---

## IDEA 本地 Run

适合单步调试、改完立即生效的场景。中间件需要自己启动。

### 第一步：中间件启动

**MySQL**（必须先建库 + 导入 SQL）：

```powershell
# MySQL 服务确认运行
Get-Service -Name MySQL* | Select Status, Name, DisplayName

# 一键建库 + 导入（所有表都在这一个文件里）
cd D:\aMy\myprojects\shopHub
mysql -uroot -p840614 < resources\db\init.sql
```

**ZooKeeper**：

```powershell
cd D:\software\apache-zookeeper-3.9.x-bin
.\bin\zkServer.cmd
# 等 STARTED
```

**RocketMQ**（两个新窗口）：

```powershell
# 窗口 1：NameServer
cd D:\software\rocketmq-4.9.7
start .\bin\mqnamesrv.cmd

# 窗口 2：Broker
start .\bin\mqbroker.cmd -n 127.0.0.1:9876
```

**Redis**（Windows 原生或 Docker）：

```powershell
# 方式 A：Windows 原生
cd D:\software\redis
.\redis-server.exe redis.windows.conf

# 方式 B：Docker（更简单）
docker run -d --name shop-redis -p 6379:6379 redis:7-alpine redis-server --requirepass 840614
```

### 第二步：修改连接配置

所有 `application.yaml` 默认值（以 shop-seckill 为例）：

```yaml
spring:
  redis:
    host: ${REDIS_HOST:192.168.6.128}     # ← 旧 VM 地址，本地设 REDIS_HOST=localhost
    port: 6379
    password: ${REDIS_PASSWORD:840614}
  datasource:
    url: jdbc:mysql://${MYSQL_HOST:localhost}:3306/shophub?useSSL=false&serverTimezone=Asia/Shanghai
    username: root
    password: ${MYSQL_PASSWORD:840614}

dubbo:
  registry:
    address: ${DUBBO_REGISTRY_ADDRESS:zookeeper://127.0.0.1:2181}

rocketmq:
  name-server: ${ROCKETMQ_NAMESRV:127.0.0.1:9876}
```

⚠️ **推荐用环境变量覆盖，别改 yaml**：

| 环境变量 | 本地开发值 | 说明 |
|---------|-----------|------|
| `REDIS_HOST` | `localhost` | 默认值是旧 VM `192.168.6.128` |
| `MYSQL_HOST` | `localhost` | |
| `DUBBO_REGISTRY_ADDRESS` | `zookeeper://127.0.0.1:2181` | |
| `ROCKETMQ_NAMESRV` | `127.0.0.1:9876` | |

IDEA 里在 **Run → Edit Configurations → Environment variables** 填，或加 `-DREDIS_HOST=localhost` 到 VM options。

### 第三步：IDEA 启动 5 个服务

| 顺序 | 服务 | 启动类 | 端口 |
|------|------|--------|------|
| 1 | shop-gateway | `ShopGatewayApplication` | 8080 |
| 2 | shop-user | `ShopUserApplication` | 8083 |
| 3 | shop-shop | `ShopShopApplication` | 8082 |
| 4 | shop-seckill | `ShopSeckillApplication` | **8081** |
| 5 | shop-pay | `ShopPayApplication` | 8084 |

> 顺序灵活，只要 ZK + RMQ + MySQL + Redis 都就绪就行。shop-seckill 和 shop-pay 依赖 Dubbo Provider（shop-user 等先注册）。

### 启动验证

```powershell
# Gateway Health
curl http://localhost:8080/actuator/health

# Knife4j（秒杀服务直开）
start http://localhost:8081/doc.html

# ZK 注册（看 Dubbo providers）
# zkCli.cmd -server 127.0.0.1:2181 -cmd ls /dubbo/provider
```

---

## 快速复位（秒杀压测前必跑）

每次 T1-T6 场景开始前：

```bash
# 方式 1：Git Bash（最方便）
cd /d/aMy/myprojects/shopHub
bash scripts/gen-loadtest-tokens.sh 5000   # 生成 5000 token + 写 Redis
bash scripts/reset-seckill.sh 10 100        # 清订单 + warmup + 设库存
```

```powershell
# 方式 2：手动分步（PowerShell）
cd D:\aMy\myprojects\shopHub

# ① 清 DB 订单
docker exec shophub-mysql mysql -uroot -p840614 shophub -e "DELETE FROM tb_voucher_order WHERE voucher_id=10; DELETE FROM tb_seckill_message_log WHERE voucher_id=10;"

# ② Reset Redis 库存 + 一人一单 Set（⚠️ key 是 seckill:order:10，不是 seckill:users:10）
docker exec shophub-redis sh -c "redis-cli --no-auth-warning -a 840614 SET seckill:stock:10 100; redis-cli --no-auth-warning -a 840614 DEL seckill:order:10"

# ③ 手动塞测试 token（tokens.csv 是假值，Redis 里没有）
docker exec shophub-redis sh -c "redis-cli --no-auth-warning -a 840614 HSET login:token:loadtest1 id 1 nickName Tester icon ''; redis-cli --no-auth-warning -a 840614 EXPIRE login:token:loadtest1 1800"
```

> ⚠️ **warmup 不需要带 voucherId 参数**，`POST /voucher-order/warmup` 全量预热所有 `tb_seckill_voucher` 的 Redis 库存 + 清空所有 `seckill:order:{voucherId}` Set。

---

## 端口映射速查

| 端口 | 服务 | 说明 |
|------|------|------|
| 8080 | shop-gateway | HTTP 路由层（秒杀压测**绕过**） |
| **8081** | **shop-seckill** | **秒杀服务，压测直连端口** |
| 8082 | shop-shop | 商铺 CRUD（缓存一致性 T4、多级缓存 T5） |
| 8083 | shop-user | 用户登录/验证码 |
| 8084 | shop-pay | 支付 Outbox + 对账 |
| 2181 | ZooKeeper | Dubbo 注册中心 |
| 9876 | RMQ NameServer | RocketMQ 地址 |
| 10911 | RMQ Broker | RocketMQ 消息代理 |
| 3306 | MySQL | root / 840614 |
| 6379 | Redis | 密码 840614 |
