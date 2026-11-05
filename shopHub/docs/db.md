# 数据库设计

ShopHub 使用 **两个独立的 MySQL 库**：
- `shophub` — 业务数据（用户、商铺、券、秒杀订单）
- `shophub_pay` — 支付数据（支付订单、Outbox 消息表）

init.sql文件在shopHub\resources\db里

---

## ER 关系总览

```
┌──────────────┐        ┌──────────────┐
│   tb_user    │        │ tb_user_info │
└──────┬───────┘        └──────────────┘
       │ 1:1 (user_id = tb_user.id)
       │
       │ 下单
       ▼
┌──────────────────┐   ┌──────────────┐   ┌────────────────┐
│ tb_voucher_order │──▶│  tb_voucher  │──▶│ tb_seckill_voucher │
└────────┬─────────┘   └──────┬───────┘   └────────────────┘
         │                     │
         │ 关联商铺             │ 商铺类型
         ▼                     ▼
┌──────────────┐        ┌──────────────┐
│   tb_shop    │──▶    │ tb_shop_type  │
└──────────────┘        └──────────────┘


shophub_pay 库:

┌──────────────────┐      ┌────────────────────────┐
│   tb_pay_order    │      │ tb_pay_message_temp    │
│ (支付订单 5态状态机)│      │ (Outbox 本地消息表)      │
└──────────────────┘      └────────────────────────┘
```

> 📸 [ER 图截图 — 建议用 DBeaver 或 Draw.io 导出]

---

## 表清单

### shophub 库

| 表 | 服务 | 说明 |
|----|------|------|
| `tb_user` | shop-user | 用户表（手机号唯一） |
| `tb_user_info` | shop-user | 用户扩展信息（1:1） |
| `tb_shop` | shop-shop | 商铺表（多级缓存目标） |
| `tb_shop_type` | shop-shop | 商铺类型 |
| `tb_voucher` | shop-seckill | 优惠券（普通券 + 秒杀券，type 区分） |
| `tb_seckill_voucher` | shop-seckill | 秒杀券扩展（1:1，含库存/有效期） |
| `tb_voucher_order` | shop-seckill | 秒杀订单（一人一单唯一索引） |
| `tb_seckill_message_log` | shop-seckill | MQ 消费日志（幂等 + 死信记录） |

### shophub_pay 库

| 表 | 服务 | 说明 |
|----|------|------|
| `tb_pay_order` | shop-pay | 支付订单（5 态状态机） |
| `tb_pay_message_temp` | shop-pay | Outbox 本地消息表 |

---

## 核心表 DDL

### tb_user — 用户表

```sql
CREATE TABLE `tb_user` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `phone`        VARCHAR(11)     NOT NULL                COMMENT '手机号码（登录账号）',
  `password`     VARCHAR(128)    DEFAULT ''              COMMENT '密码，BCrypt 加密存储',
  `nick_name`    VARCHAR(32)     DEFAULT ''              COMMENT '昵称',
  `icon`         VARCHAR(255)    DEFAULT ''              COMMENT '头像 URL',
  `create_time`  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `unique_key_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';
```

**索引设计**：`phone` 唯一索引 — 短信登录时 `SELECT * FROM tb_user WHERE phone = ?` 必须唯一返回。

---

### tb_voucher — 优惠券主表

```sql
DROP TABLE IF EXISTS `tb_voucher`;
CREATE TABLE `tb_voucher` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `shop_id`     BIGINT UNSIGNED NULL DEFAULT NULL COMMENT '商铺id',
  `title`       VARCHAR(255)    NOT NULL COMMENT '代金券标题',
  `sub_title`   VARCHAR(255)    NULL DEFAULT NULL COMMENT '副标题',
  `rules`       VARCHAR(1024)   NULL DEFAULT NULL COMMENT '使用规则',
  `pay_value`   BIGINT(10) UNSIGNED NOT NULL COMMENT '支付金额（分）',
  `actual_value` BIGINT(10)     NOT NULL COMMENT '抵扣金额（分）',
  `type`        TINYINT(1) UNSIGNED NOT NULL DEFAULT 0 COMMENT '0:普通券  1:秒杀券',
  `status`      TINYINT(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '1:上架  2:下架  3:过期',
  `create_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券';
```

---

### tb_seckill_voucher — 秒杀券扩展

```sql
DROP TABLE IF EXISTS `tb_seckill_voucher`;
CREATE TABLE `tb_seckill_voucher` (
  `voucher_id`  BIGINT UNSIGNED NOT NULL COMMENT '关联的优惠券的id',
  `stock`       INT             NOT NULL COMMENT '库存',
  `create_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `begin_time`  TIMESTAMP       NOT NULL DEFAULT '1970-01-01 08:00:01' COMMENT '生效时间',
  `end_time`    TIMESTAMP       NOT NULL DEFAULT '1970-01-01 08:00:01' COMMENT '失效时间',
  `update_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`voucher_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀优惠券表，与优惠券是一对一关系';
```

**设计要点**：两张表通过 `voucher_id` 一对一关联，**秒杀库存独立在 `tb_seckill_voucher.stock` 字段**。扣减库存走 SQL：

```sql
UPDATE tb_seckill_voucher
SET stock = stock - 1
WHERE voucher_id = ? AND stock > 0
```

`stock > 0` 条件天然防超卖（乐观锁 + 行锁）。

---

### tb_voucher_order — 秒杀订单 

```sql
CREATE TABLE `tb_voucher_order` (
  `id`          BIGINT          NOT NULL COMMENT '主键（RedisIdWorker 雪花ID）',
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '下单用户',
  `voucher_id`  BIGINT UNSIGNED NOT NULL COMMENT '优惠券',
  `pay_type`    TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1:余额 2:支付宝 3:微信',
  `status`      TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '订单状态',
  `create_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `pay_time`    TIMESTAMP       DEFAULT NULL,
  `use_time`    TIMESTAMP       DEFAULT NULL,
  `refund_time` TIMESTAMP       DEFAULT NULL,
  `update_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_voucher` (`user_id`, `voucher_id`) COMMENT '一人一单核心索引',
  KEY `idx_voucher_id` (`voucher_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀订单';
```

**订单状态机**：

| 值 | 含义 | Controller 是否用 | 触发 |
|----|------|-------------------|------|
| 1 | 未支付（待支付） | ✅ | 下单初始态 |
| 2 | 已支付 | ✅ | PAY_SUCCESS 回调 |
| 3 | 已核销 | ✅ | 调用 use/{id} 核销接口 |
| 4 | 已取消 | ✅ | 超时关闭 / 用户主动取消 / PAY_FAIL 回调 / 退款 |

**为什么 `uk_user_voucher` 是一人一单的核心？**
秒杀下单的三道防线：
1. **Redis Lua** 原子判断一人一单（`SADD seckill:order:{voucherId} {userId}`，返回 0 表示已存在）+ 原子预扣库存（`DECRBY seckill:stock:{voucherId} 1`）— 挡掉 4900+ 请求（T1 实测）
2. **DB 唯一索引** `uk_user_voucher` — 最终兜底（Redis + Lua 失败或回滚时防重复）
3. **RocketMQ 消费幂等** `tb_seckill_message_log.uk_order_id` — 消息重试不重复写订单

---

### tb_seckill_message_log — MQ 消费日志

```sql
CREATE TABLE `tb_seckill_message_log` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `order_id`   BIGINT       NOT NULL COMMENT '秒杀订单ID',
  `user_id`    BIGINT       NOT NULL,
  `voucher_id` BIGINT       NOT NULL,
  `status`     TINYINT      NOT NULL DEFAULT 0 COMMENT '0:处理中 1:已消费 2:已入死信',
  `retry_count` INT         NOT NULL DEFAULT 0 COMMENT '重试次数',
  `error_msg`  VARCHAR(500) DEFAULT NULL,
  `create_time` DATETIME    DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_id` (`order_id`),
  KEY `idx_voucher_id` (`voucher_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀消息消费日志';
```

**为什么要消费日志表？**
RocketMQ 至少一次投递 — 同一个消息可能被多次消费。`uk_order_id` 唯一索引保证即使消息重投，也不会产生新订单。失败后 `retry_count` 递增，达到 `max-retry`（默认 3）后置 `status=2` 并投递死信 Topic。

---

### tb_pay_order — 支付订单（shophub_pay 库）

```sql
CREATE TABLE `tb_pay_order` (
  `id`              BIGINT       NOT NULL COMMENT '雪花ID',
  `order_no`        VARCHAR(64)  NOT NULL COMMENT '业务订单号（= tb_voucher_order.id）',
  `pay_no`          VARCHAR(64)  NOT NULL COMMENT '支付单号',
  `user_id`         BIGINT       NOT NULL,
  `amount`          INT          NOT NULL COMMENT '金额（分）',
  `channel`         VARCHAR(32)  NOT NULL COMMENT 'WECHAT/ALIPAY/SIMULATE',
  `status`          TINYINT      NOT NULL DEFAULT 0 COMMENT '0:待支付 1:支付中 2:已支付 3:支付失败 4:已退款',
  `callback_data`   TEXT                  COMMENT '渠道回调原始报文',
  `callback_hash`   VARCHAR(64)  DEFAULT NULL COMMENT 'SHA256 原始报文（幂等去重）',
  `pay_time`        DATETIME     DEFAULT NULL,
  `expire_time`     DATETIME     NOT NULL,
  `refund_time`     DATETIME     DEFAULT NULL,
  `refund_amount`   INT          DEFAULT NULL COMMENT '分',
  `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_no`       (`pay_no`),
  UNIQUE KEY `uk_order_no`     (`order_no`),
  UNIQUE KEY `uk_callback_hash` (`callback_hash`),
  KEY `idx_user_status`       (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付订单';
```

**支付 5 态状态机**：

```
PENDING(0) ──create──▶ 初始态
     │
     ├──callbackPay(成功)──▶ PAID(2)
     │
     ├──渠道返回失败──────▶ FAILED(3)
     │
     └──PayExpireJob 超时──▶ FAILED(3) + 写 Outbox PAY_FAIL
                                    │
PAID(2) ──refundPay────────▶ REFUNDED(4)
FAILED(3) ──用户手动取消────▶ 取消订单（shop-seckill 侧）
```

**关键索引**：

| 索引 | 作用 |
|------|------|
| `uk_pay_no` | 支付单号唯一（支付侧对账） |
| `uk_order_no` | 业务订单号唯一（秒杀订单 → 支付订单 1:1） |
| `uk_callback_hash` | 回调幂等 — 同一渠道回调报文 SHA256 不重复处理 |
| `idx_user_status` | 查用户的各状态支付单（"我的订单"） |

---

### tb_pay_message_temp — Outbox 本地消息表

```sql
CREATE TABLE `tb_pay_message_temp` (
  `id`              BIGINT       NOT NULL COMMENT '雪花ID',
  `msg_type`        VARCHAR(64)  NOT NULL COMMENT 'PAY_SUCCESS / PAY_FAIL / REFUND',
  `biz_key`         VARCHAR(128) NOT NULL COMMENT '支付单号',
  `msg_body`        TEXT         NOT NULL COMMENT '消息体 JSON',
  `mq_topic`        VARCHAR(64)  NOT NULL COMMENT 'seckill-pay-result-topic',
  `mq_tag`          VARCHAR(32)  DEFAULT NULL,
  `status`          TINYINT      NOT NULL DEFAULT 0 COMMENT '0:待发送 1:已发送 2:失败',
  `retry_count`     INT          NOT NULL DEFAULT 0,
  `next_retry_time` DATETIME     DEFAULT NULL,
  `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_retry` (`status`, `next_retry_time`) COMMENT '扫描待发送消息',
  KEY `idx_biz_key`       (`biz_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Outbox 本地消息表';
```

**为什么要 Outbox 表？**

支付状态变更（如 PENDING → PAID）必须：
1. 更新 `tb_pay_order.status = 2`
2. 发送 RocketMQ 通知 shop-seckill

如果先更新 DB 再发 MQ 但 MQ 发失败，就会出现状态已变但下游不知道 → **数据不一致**。

Outbox 模式解决：**在同一个 DB 事务里同时写 `tb_pay_order` + `tb_pay_message_temp`**，事务保证原子性。然后 `LocalMessageScanner` 定时扫描 `status=0` 的记录投递 RocketMQ。

---

### tb_shop + tb_shop_type — 商铺

```sql
-- 商铺（shop-shop 服务，多级缓存目标）
DROP TABLE IF EXISTS `tb_shop`;
CREATE TABLE `tb_shop` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`        VARCHAR(128)    NOT NULL COMMENT '商铺名称',
  `type_id`     BIGINT UNSIGNED NOT NULL COMMENT '商铺类型id',
  `images`      VARCHAR(1024)   NOT NULL COMMENT '商铺图片，多图逗号分隔',
  `area`        VARCHAR(128)    NULL DEFAULT NULL COMMENT '商圈（如：大关、拱宸桥/上塘）',
  `address`     VARCHAR(255)    NOT NULL COMMENT '详细地址',
  `x`           DOUBLE UNSIGNED NOT NULL COMMENT '经度',
  `y`           DOUBLE UNSIGNED NOT NULL COMMENT '纬度',
  `avg_price`   BIGINT UNSIGNED NULL DEFAULT NULL COMMENT '均价（整数，单位：元）',
  `sold`        INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '销量',
  `comments`    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '评论数量',
  `score`       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '评分×10（如 47 = 4.7 分）',
  `open_hours`  VARCHAR(32)     NULL DEFAULT NULL COMMENT '营业时间（如：10:00-22:00）',
  `create_time` TIMESTAMP       NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` TIMESTAMP       NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  INDEX `foreign_key_type`(`type_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商铺';

-- 商铺类型（下拉列表，几乎不变，适合全量缓存）
DROP TABLE IF EXISTS `tb_shop_type`;
CREATE TABLE `tb_shop_type` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`        VARCHAR(32)     NULL DEFAULT NULL COMMENT '类型名称',
  `icon`        VARCHAR(255)    NULL DEFAULT NULL COMMENT '图标路径',
  `sort`        INT UNSIGNED    NULL DEFAULT NULL COMMENT '显示顺序',
  `create_time` TIMESTAMP       NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` TIMESTAMP       NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商铺类型';
```

**缓存策略**：`tb_shop_type` 全量缓存（启动时 Load）；`tb_shop` 按 id 缓存（Caffeine L1 → Redis L2），写路径走延迟双删。

---

## 初始化脚本

所有表都在一个文件里：**`resources/db/init.sql`**（自动 `CREATE DATABASE shophub + shophub_pay`）

### Docker Compose 模式

Dockerfile.mysql 启动时自动导入 init.sql，**无需手动操作**。

### IDEA / 本机 MySQL 模式

```powershell
cd D:\aMy\myprojects\shopHub
mysql -uroot -p840614 < resources\db\init.sql
```

一键建两库 + 建所有表 + 索引 + 样例数据。

---

## 表间关联关系总结

| 关系 | 说明 | 设计选择 |
|------|------|----------|
| tb_voucher (1) → tb_seckill_voucher (1) | 秒杀券扩展 | 拆表：秒杀券独有库存字段独立，避免普通券也有 stock=0 |
| tb_voucher (1) → tb_voucher_order (N) | 券被多次下单 | type=1（秒杀券）才产生订单 |
| tb_user (1) → tb_voucher_order (N) | 用户多次下单 | uk_user_voucher 一人一单兜底 |
| tb_pay_order (1:1) → tb_voucher_order | 一个订单对应一个支付单 | uk_order_no 保证 |
| tb_pay_message_temp → tb_pay_order | Outbox 多对一 | 三个 msg_type：PAY_SUCCESS / PAY_FAIL / REFUND |

---

## Redis 数据结构速查

| Key | 结构 | 说明 | TTL |
|-----|------|------|-----|
| `login:code:{phone}` | String | 短信验证码 | 120s |
| `login:token:{token}` | Hash | Token → 用户信息 | 30min（滑动续期） |
| `seckill:stock:{voucherId}` | String | 秒杀库存（预热写入） | 永久 |
| `seckill:order:{voucherId}` | Set（userId 做 member）| 一人一单标记，warmup 时 SREM 全部清空 | 永久 |
| `cache:shop:{id}` | String (JSON) | 商铺详情缓存（Caffeine L1 miss 后查 Redis） | 30min（固定，CACHE_SHOP_TTL=30） |
| `rate_limit:{key}` | ZSet | 滑动窗口限流计数 | 窗口时间 |

---

## 常见数据库维护 SQL

```sql
-- 对账：秒杀库存 vs DB 一致性检查
SELECT '秒杀券' AS obj, voucher_id, stock
FROM tb_seckill_voucher
UNION ALL
SELECT '订单数', voucher_id, COUNT(*)
FROM tb_voucher_order
WHERE status IN (1, 2, 3)
GROUP BY voucher_id;

-- 支付过期订单（手动扫一次）
SELECT * FROM shophub_pay.tb_pay_order
WHERE status = 0 AND expire_time < NOW() LIMIT 100;

-- Outbox 待发送积压
SELECT status, COUNT(*) FROM shophub_pay.tb_pay_message_temp
GROUP BY status;
```
