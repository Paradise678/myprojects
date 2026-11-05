# ShopHub・店汇通 — 微服务高并发秒杀平台

> 基于 Spring Boot 2.7 + Dubbo 3.2 + RocketMQ 的多模块微服务架构，覆盖 **秒杀下单 → 支付 → 核销 → 退款** 完整交易链路。核心亮点：Redis Lua 原子预扣 + RocketMQ 事务消息削峰、支付 5 态状态机 + 渠道 SPI 扩展、库存双重回补（DB + Redis）、Knife4j + Actuator/Micrometer 内置可观测端点。

---

## 📋 项目简介

**ShopHub・店汇通** 是一个多模块微服务架构的秒杀平台。ShopHub 将业务拆分为 5 个独立服务 + 1 个 Gateway，通过 **Dubbo RPC** 实现服务间调用、**ZooKeeper** 作为注册中心、**RocketMQ** 处理秒杀事务消息与支付结果异步通知。

核心业务链路：

```
用户登录 → 浏览商铺 → 抢购秒杀券 → 下单（Lua 预扣 + MQ 异步削峰）
  → 支付（状态机 5 态流转，SPI 支持多渠道）
  → 核销（一人一单校验）
  → 退款（双重回补 + 幂等回调）
```

---

## 🛠 技术栈

| 分类 | 技术 | 版本 | 说明 |
|------|------|------|------|
| 核心框架 | Spring Boot | 2.7.18 | JDK 17 |
| 微服务 RPC | Apache Dubbo | 3.2.7 | Tripe 协议 + Curator 5.7.1 + ZK 3.9.3 Client |
| 服务网关 | Spring Cloud Gateway | 2021.0.8 | 路由转发 + Actuator Gateway 端点 |
| 服务注册 | Apache ZooKeeper | 3.9 (Docker) / 3.8.7 (本机) | Dubbo 注册中心 |
| 持久层 | MyBatis-Plus | 3.5.3.1 | 通用 CRUD + 分页插件 |
| 数据库 | MySQL | 8.0 | 两个库：`shophub`（业务）+ `shophub_pay`（支付） |
| 缓存 | Redis | 7.x | 缓存 / 会话 / 锁 / Lua 原子预扣 / 滑动窗口限流 |
| 分布式锁 | Redisson | 3.23.5 | 可重入锁 + 看门狗 |
| 本地缓存 | Caffeine | 2.9.3 | 商铺热点数据 L1 缓存 |
| 消息队列 | RocketMQ | 4.9.7 (Server，Docker) / 5.5.1 (本机) / 2.3.4 (Spring Boot Starter) | 事务消息（秒杀下单）+ 异步通知（支付结果） |
| API 文档 | Knife4j | 4.5.0 | OpenAPI 3 增强 UI |
| 可观测 | Actuator + Micrometer（Prometheus exposition） | - | 各服务自带 `/actuator/prometheus` 端点，可接入任意 Prometheus 实例 |
| 其他 | Hutool / Lombok / Maven | - | 工具库 / 简化代码 / 构建 |

---

## 🏗 项目架构

```
                         ┌─────────────────────┐
                         │  Actuator Prometheus │ ← 每服务自带 /actuator/prometheus
                         │  (独立 Prome/Grafana │
                         │   未部署，可选)      │
                         └─────────────────────┘
                                    │
┌───────────┐   HTTP     ┌──────────┴──────────┐     Dubbo RPC      ┌──────────────┐
│  客户端    │ ─────────▶│   shop-gateway 8080  │ ──────────────────▶ │  shop-user   │
│ Postman   │            │   (Spring Cloud GW)  │                     │    8083      │
│ Knife4j   │            └──────────┬──────────┘                     │  Dubbo 随机  │
└───────────┘                       │ Path 路由                        └──────┬───────┘
                                    │                                        │
                    ┌───────────────┼───────────────┬─────────────────────┐  │
                    ▼               ▼               ▼                     ▼  │
            ┌──────────────┐ ┌──────────────┐ ┌──────────────┐     ┌──────────────┐
            │  shop-shop   │ │ shop-seckill │ │  shop-pay    │     │  ZooKeeper   │
            │    8082      │ │    8081      │ │    8084      │     │   2181       │
            │ Dubbo 随机   │ │ Dubbo 随机   │ │ Dubbo 随机   │     │ (注册中心)   │
            └──────┬───────┘ └──────┬───────┘ └──────┬───────┘     └──────────────┘
                   │                │                │
                   ▼                ▼                ▼
            ┌──────────────────────────────────────┐
            │              中间件层                  │
            │  Redis   {host}:6379                  │
            │  MySQL   localhost:3306               │
            │  RocketMQ NameServer 127.0.0.1:9876   │
            └──────────────────────────────────────┘
```

### 服务端口总览

| Port | 模块 | Dubbo 端口 | Knife4j | Prometheus | 说明 |
|------|------|-----------|---------|------------|------|
| 8080 | shop-gateway | — | ❌ | ✅ | 统一入口，秒杀压测**绕过** |
| 8081 | shop-seckill | 随机（`port: -1`） | ✅ /doc.html | ✅ | 秒杀、消息生产消费 |
| 8082 | shop-shop | 随机 | ✅ /doc.html | ✅ | 商铺浏览、多级缓存 |
| 8083 | shop-user | 随机 | ✅ /doc.html | ✅ | 用户登录、验证码、Token |
| 8084 | shop-pay | 随机 | ✅ /doc.html | ✅ | 支付创建、回调、退款、过期关闭 |

> Dubbo RPC 端口配置为 `-1`，启动时由 ZK 随机分配（20880-20883 是预留历史值，实际以运行时为准）。

### Dubbo RPC 调用关系

```
shop-seckill ──dubbo──▶ shop-user  (查询用户信息)
shop-seckill ──dubbo──▶ shop-pay   (创建支付、查询状态、取消、退款)
shop-gateway ──http──▶ 各服务
```

> Gateway 路由：`/voucher/**`, `/voucher-order/**`, `/seckill/**` → shop-seckill:8081。**正常用户流量走 Gateway，JMeter 压测直连 8081（绕过 Gateway 避免额外变量）**。

---

## 📂 项目结构

```
shopHub/
├── pom.xml                          # 父 POM：统一版本管理 + 6 个模块
│
├── shop-common/                     # 公共模块（所有服务依赖）
│   └── src/main/java/com/shophub/
│       ├── service/IPayService.java              # Dubbo 支付接口（cancel/refund/query）
│       ├── dto/                                  # PayCreateDTO, PayResultDTO, UserDTO, Result
│       ├── utils/                                # RedisIdWorker, CacheClient, 双拦截器, Key 常量
│       ├── annotation/RateLimit.java              # 限流注解
│       ├── aspect/RateLimitAspect.java           # 限流切面
│       ├── metrics/BusinessMetrics.java           # Micrometer 业务指标
│       └── exception/                            # BusinessException, DubboRemoteException, RateLimitException
│
├── shop-gateway/                    # Spring Cloud Gateway 统一入口
│   └── src/main/resources/application.yaml        # 路由规则 + Actuator 暴露
│
├── shop-user/                       # 用户服务：登录、验证码、Token
│   └── src/main/java/com/shophub/
│       ├── controller/UserController.java         # /user/code, /user/login, /user/me
│       ├── service/impl/UserServiceImpl.java     # 验证码发送 / 登录逻辑
│       ├── config/ShopUserMvcConfig.java          # 双拦截器 + Knife4j 路径排除
│       └── entity/User.java, UserInfo.java
│
├── shop-shop/                       # 商铺服务：浏览、类型下拉、多级缓存
│   └── src/main/java/com/shophub/
│       ├── controller/ShopController.java         # /shop/{id}, /shop (写)
│       ├── controller/ShopTypeController.java     # /shop-type/list
│       ├── service/impl/ShopServiceImpl.java      # CacheClient 延迟双删
│       └── config/LocalCacheConfig.java           # Caffeine L1 本地缓存
│
├── shop-seckill/                    # 秒杀服务：秒杀下单、事务消息、支付结果消费
│   └── src/main/java/com/shophub/
│       ├── controller/VoucherOrderController.java # /voucher-order/seckill, /use/{id}, /cancel/{id}
│       ├── mq/
│       │   ├── SeckillOrderProducer.java          # RocketMQ 事务消息生产者
│       │   ├── SeckillTransactionListener.java    # 半消息 → 本地事务 → DB 扣减 + Redis 回滚
│       │   ├── SeckillOrderConsumer.java           # 订单落库（幂等唯一索引）
│       │   └── PayResultConsumer.java              # 支付结果通知 — 核销 / 退款双重回补
│       ├── job/SeckillReconcileJob.java           # 定时对账 Redis vs DB
│       └── resources/lua/seckill_mq.lua            # 库存判断 + 一人一单 + 原子预扣
│
├── shop-pay/                        # 支付服务：5 态状态机 + SPI 多渠道
│   └── src/main/java/com/shophub/
│       ├── controller/PayController.java           # /api/pay/create, /query, /callback, /refund, /cancel
│       ├── service/impl/PayServiceImpl.java       # 5 态流转 + 幂等去重
│       ├── job/PayExpireJob.java                   # PENDING 过期 → FAILED + Outbox PAY_FAIL
│       ├── job/LocalMessageScanner.java            # Outbox 表扫描投递
│       ├── pay/spi/
│       │   ├── PayChannel.java                     # SPI 接口
│       │   ├── SimulatePayChannel.java             # 模拟支付（本地测试）
│       │   ├── WechatPayChannel.java               # 微信（桩实现）
│       │   └── AlipayPayChannel.java               # 支付宝（桩实现）
│       └── entity/PayOrder.java                    # STATUS_PENDING/PAYING/PAID/FAILED/REFUNDED
│
└── docs/                            # 详细文档（见下）
    ├── api.md
    ├── db.md
    ├── testcase.md
    ├── deploy.md
    ├── stress.md
    └── images/                      # 放截图
```

---

## 💡 核心功能与技术亮点

### 1. 秒杀下单：Redis Lua 预扣 + RocketMQ 事务消息削峰

```
秒杀请求
  → 正常流量走 Gateway:8080 → JMeter 压测直连 shop-seckill:8081
  → shop-seckill 调用 Redis Lua（seckill_mq.lua）原子预扣：
      ① 判断库存 > 0？
      ② 一人一单（SADD seckill:order:{voucherId} {userId}，返回 0 表示已存在）
      ③ Redis 库存 -1
  → 成功 → 发送 RocketMQ 半消息（事务消息）
  → 本地事务：扣 DB 库存 + 写订单
      成功 → COMMIT → 消费者可见
      失败 → ROLLBACK + Redis 预扣回滚（Lua 回滚脚本）
  → 消费者（SeckillOrderConsumer）：
      ① 幂等检查（tb_seckill_message_log 唯一索引）
      ② 下游业务（支付创建等）
  → Broker 回查：订单已落库则 COMMIT，否则回滚
```

**为什么用事务消息？** 秒杀瞬时流量（5000 并发）全打 DB 会造成行锁竞争和连接池耗尽。Redis 预扣把流量挡在门外（Lua 原子操作单机 10w+ QPS），MQ 异步削峰让 DB 按自己的节奏处理。

**为什么要回滚 Redis 预扣？** 本地事务失败时，DB 没有扣库存，但 Redis 已经扣了，会导致库存不一致。

核心代码：`shop-seckill/mq/`、`shop-seckill/resources/lua/seckill_mq.lua`

---

### 2. 支付 5 态状态机 + SPI 渠道扩展

支付订单有 5 个状态，由 `PayOrder.STATUS_*` 常量定义：

```
                    callbackPay(成功)
    PENDING ──────────────────────────▶ PAID
       │                                  │
       │ expire (超时)                    │ refundPay
       ▼                                  ▼
    FAILED                             REFUNDED
       │
       │ (过期自动关闭)
       ▼
    FAILED (Outbox PAY_FAIL → 通知秒杀服务回补库存)
```

| 状态 | 常量 | 值 | 说明 |
|------|------|---|------|
| 待支付 | STATUS_PENDING | 0 | 订单刚创建，等用户支付 |
| 支付中 | STATUS_PAYING | 1 | 用户调起渠道支付 |
| 已支付 | STATUS_PAID | 2 | 渠道回调成功 |
| 支付失败 | STATUS_FAILED | 3 | 渠道返回失败 或 超时自动关闭 |
| 已退款 | STATUS_REFUNDED | 4 | 退款完成 |

**SPI 渠道扩展**：`PayChannel.java` 定义统一接口（`pay()` / `refund()` / `query()`），三个实现 `SimulatePayChannel`（本地模拟，无外部依赖）、`WechatPayChannel`、`AlipayPayChannel`。新增渠道只需实现接口 + Spring Bean。

**幂等去重**：回调接口用 `tb_pay_order.callback_hash`（SHA256 原始报文）唯一索引，同一回调不重复处理。

核心代码：`shop-pay/service/impl/PayServiceImpl.java`、`shop-pay/pay/spi/`、`shop-pay/entity/PayOrder.java`

---

### 3. 支付结果异步通知：Outbox + RocketMQ 双向库存回补

支付结果通过 RocketMQ 异步通知 shop-seckill 服务：

```
shop-pay 状态变更
  → 写 Outbox 表 tb_pay_message_temp（本地消息表）
  → LocalMessageScanner 定时扫描 → 投递 RocketMQ
  → PayResultConsumer（shop-seckill）消费：
      msgType = PAY_SUCCESS → 核销（use/{orderId}）
      msgType = PAY_FAIL    → 取消订单 + DB 库存 +1 + Redis 库存 +1
      msgType = PAY_REFUND  → 退款双重回补（DB +1, Redis +1）
```

**为什么要双重回补？**

支付失败或退款时，秒杀阶段 Redis 已经预扣了库存（Lua 原子操作），DB 也扣了库存。回补时必须两边都加回去，否则：
- 只回 DB → Redis 库存永远少 1 → 后续秒杀一直"库存不足"
- 只回 Redis → DB 库存比 Redis 多 → 新秒杀可能超卖

**乐观锁**：`PayResultConsumer` 对 REFUND 消息放宽阔乐观锁条件 `status IN (2, 3)`（PAID 或 FAILED），因为退款可能在支付成功后或超时关闭后触发。

核心代码：`shop-seckill/mq/PayResultConsumer.java`、`shop-pay/job/PayExpireJob.java`

---

### 4. 商铺多级缓存 + 延迟双删

商铺浏览是高频读场景，使用 **Caffeine（L1）→ Redis（L2）→ MySQL** 三级缓存链路：

```
GET /shop/{id}
  → Caffeine 命中？→ 直接返回
  → Redis 命中？→ 回填 Caffeine → 返回
  → MySQL 命中？→ 写 Redis + Caffeine → 返回
  → 都没有 → 空值缓存（防穿透，短 TTL）
```

**缓存一致性：延迟双删**

- **写路径**（ShopServiceImpl.update）：更新 DB → 事务提交后删 Caffeine + Redis → 500ms 后二次删除
- **为什么提交后才删？** 事务未提交时删缓存，并发读会立刻回源旧值写回缓存，等于白删
- **为什么双删？** 清理"读线程读到旧值、删缓存后又回填"的小窗口脏数据
- **多实例**：其他实例的 Caffeine 无法直接删除，靠 5s TTL 兜底

**可观测**：Caffeine 命中情况走 Micrometer cache 指标：`cache_gets_total{cache="shopLocalCache"}`。

核心代码：`shop-common/utils/CacheClient.java`、`shop-shop/config/LocalCacheConfig.java`、`shop-shop/service/impl/ShopServiceImpl.java`

---

### 5. 接口限流：Redis ZSet 滑动窗口

基于 `@RateLimit` 注解 + Lua 原子脚本（`rate_limit.lua`）的滑动窗口限流，比固定窗口没有临界突刺。

| 接口 | 维度 | 限制 |
|------|------|------|
| `POST /voucher-order/seckill/{id}` | 登录用户 | 1 秒 10 次 |
| `POST /user/code` | 客户端 IP | 60 秒 5 次 |
| `POST /user/login` | 客户端 IP | 60 秒 10 次 |

超限返回 `429 Too Many Requests`，响应头带 `Retry-After`。总开关 `rate-limit.enabled`（压测时可关闭排除干扰）。

核心代码：`shop-common/annotation/RateLimit.java`、`shop-common/aspect/RateLimitAspect.java`、`shop-common/resources/lua/rate_limit.lua`

---

## 🚀 快速启动

**详细部署步骤、环境变量开关、复位命令、IDEA Run 配置见 [docs/deploy.md](./docs/deploy.md)**。

最简 Docker Compose 一键拉起：

```powershell
mvn clean install -DskipTests       # 本机预编译（Docker 容器访问不了 Maven 仓库）
docker compose up -d --build        # 10 容器：4 中间件 + 5 业务服务 + 1 Gateway
docker compose ps                   # 确认全 Healthy
```

容器端口速查：

| 端口 | 服务 | Knife4j |
|------|------|---------|
| **8081** | **shop-seckill（秒杀）** | ✅ /doc.html |
| 8082 | shop-shop（商铺） | ✅ /doc.html |
| 8083 | shop-user（用户） | ✅ /doc.html |
| 8084 | shop-pay（支付） | ✅ /doc.html |
| 8080 | shop-gateway（网关） | ❌ |

> 秒杀压测直连 **8081**（绕过 Gateway），正常用户流量走 8080 → Gateway 路由到 8081。

---

## 📚 详细文档

| 文档 | 路径 | 说明 |
|------|------|------|
| 数据库设计 | [docs/db.md](docs/db.md) | 两个库的 DDL、ER 图、关键字段说明 |
| 接口文档 | [docs/api.md](docs/api.md) | 4 个服务 28 个接口，请求/响应/示例 |
| 功能测试用例 | [docs/testcase.md](docs/testcase.md) | 秒杀、支付、核销、退款、过期关闭 8 个核心用例 |
| 部署文档 | [docs/deploy.md](docs/deploy.md) | 完整部署步骤 + 运维控制台 + 常见问题排查 |
| 压测报告 | [docs/stress.md](docs/stress.md) | T1-T6 六场景压测方案 + JMeter 脚本 + 结果截图 + 踩坑速查 |

