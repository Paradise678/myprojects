# ShopHub・店汇通 API 接口文档

## 通用说明

### 统一响应体

所有业务接口返回以下统一结构（HTTP 200）：

```json
{
  "success": true,
  "errorMsg": "错误信息（success=false 时有值）",
  "data": {},
  "total": 100
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| success | boolean | 是否成功 |
| errorMsg | string | 错误信息，仅在 success=false 时填充 |
| data | any | 业务数据：对象 / 字符串 / 数字 / 数组 |
| total | long | 分页总条数（列表接口返回） |

HTTP 状态码语义：
- **200** — 业务正常处理（逻辑成败看 `success` 字段）
- **401** — 未登录 / Token 无效 / Token 过期（login 拦截器返回）
- **400** — 参数校验失败
- **500** — 服务内部异常

### 鉴权说明

**两层拦截器**：RefreshTokenInterceptor（所有路径） + LoginInterceptor（需登录路径）

| Header | 是否必选 | 说明 |
|--------|----------|------|
| `authorization` | 登录接口必带 | 值为 login 返回的 32 位 UUID 紧凑字符串，**不加 Bearer 前缀** |
| `X-Callback-Secret` | 仅 pay/callback | 值为 `shophub-callback-dev`，生产环境从配置读取 |
| `Content-Type` | POST/PUT 必带 | `application/json` |

Redis Token Key 格式：`login:token:{token}`（Hash 结构，TTL 30 分钟滑动续期）

### 各服务地址

| 服务 | 端口 | 基础 URL | 核心 Controller |
|------|------|----------|-----------------|
| shop-user | 8083 | `http://localhost:8083` | UserController |
| shop-shop | 8082 | `http://localhost:8082` | ShopController, ShopTypeController |
| shop-seckill | 8081 | `http://localhost:8081` | VoucherController, VoucherOrderController |
| shop-pay | 8084 | `http://localhost:8084` | PayController |

### 数据库连接

```
Host: 127.0.0.1
Port: 3306
DB:   shophub / shophub_pay
User: root
Pwd:  840614
```

---

## shop-user 用户服务 (:8083)

> 短信验证码登录（2 分钟有效）+ Token 鉴权 + 用户资料
> Controller: `UserController`

### 1.1 发送手机验证码

```
POST /user/code
```

按 IP 限流 60 秒 5 次。验证码存 Redis `login:code:{phone}`，2 分钟有效。手机号必须符合中国大陆号码正则 `^1[3-9]\d{9}$`。

**请求参数：**

| 名称 | 位置 | 类型 | 必选 | 说明 |
|------|------|------|------|------|
| phone | query | string | ✅ | 11 位手机号 |

**返回数据（开发环境）：**

```json
{
  "success": true,
  "errorMsg": null,
  "data": "382914"
}
```

> 生产环境 `app.dev.expose-code=false` 时 `data=null`。开发调试环境默认 `true`。

---

### 1.2 用户登录

```
POST /user/login
```

手机号+验证码 或 手机号+密码。**首次登录自动注册**。返回 32 位 Token，客户端存 `localStorage` 后每次请求放 `authorization` Header。

**请求体：**

```json
{
  "phone": "13800138000",
  "code": "382914"
}
```

| 字段 | 类型 | 必选 | 说明 |
|------|------|------|------|
| phone | string | ✅ | 11 位手机号 |
| code | string | ❌ | 短信验证码（phone+code 登录方式）|
| password | string | ❌ | 密码（phone+password 登录方式）|

**返回数据：**

```json
{
  "success": true,
  "errorMsg": null,
  "data": "a1b2c3d4e5f67890abcdef1234567890"
}
```

| 失败 errorMsg | 原因 |
|---------------|------|
| 手机号格式错误！ | phone 不符合正则 |
| 验证码错误 | Redis 里没此 code / code 不匹配 / 已使用 |

---

### 1.3 获取当前登录用户

```
GET /user/me
```

| 返回 | 说明 |
|------|------|
| 401 | Token 无效 / 未登录 |
| data | UserDTO `{id, nickName, icon}` |

---

### 1.4 按 ID 查询用户

```
GET /user/{id}
```

**路径参数：**

| 名称 | 类型 | 说明 |
|------|------|------|
| id | long | 用户自增主键 |

> 公开接口，无需鉴权（放行路径）。data 为脱敏后的 UserDTO。

---

### 1.5 查询用户详情

```
GET /user/info/{id}
```

返回 UserInfo（昵称以外的扩展信息）。不存在时返回空 data。

---

## shop-shop 商铺服务 (:8082)

> 商铺浏览、类型下拉、Caffeine→Redis→MySQL 三级缓存、延迟双删
> Controller: `ShopController`, `ShopTypeController`

### 2.1 按 ID 查询商铺详情

```
GET /shop/{id}
```

**路径参数：**

| 名称 | 类型 | 说明 |
|------|------|------|
| id | long | 商铺自增主键 |

**缓存策略**：先查 Caffeine 本地缓存（TTL 5s，进程内）→ Redis（30 分钟，`cache:shop:{id}`）→ MySQL

> TTL 短（5s）是多实例部署下本地缓存不一致的时间上限，属于一致性取舍。热点 Key 流量被 Caffeine 100% 吸收，保护 Redis。

---

### 2.2 按类型分页查询商铺

```
GET /shop/of/type
```

**请求参数：**

| 名称 | 位置 | 类型 | 必选 | 说明 |
|------|------|------|------|------|
| typeId | query | int | ✅ | 商铺类型 ID |
| current | query | int | ❌ | 页码，默认 1 |
| x | query | double | ❌ | 经度（传了则按距离排序）|
| y | query | double | ❌ | 纬度 |

---

### 2.3 按名称关键字分页查询商铺

```
GET /shop/of/name
```

**请求参数：**

| 名称 | 位置 | 类型 | 必选 | 说明 |
|------|------|------|------|------|
| name | query | string | ❌ | 关键字（模糊匹配）|
| current | query | int | ❌ | 页码，默认 1 |

> 分页 size 固定 10，Controller 层直接用 MyBatis-Plus `.page(10)` 。

---

### 2.4 新增商铺

```
POST /shop
```

**请求体 (Shop)：**

```json
{
  "name": "老北京炸酱面",
  "typeId": 1,
  "images": "https://xxx.com/a.jpg",
  "area": "朝阳区",
  "address": "三里屯路 19 号",
  "x": 116.458,
  "y": 39.935,
  "avgPrice": 48
}
```

成功后 `data` 为新商铺自增 ID。

---

### 2.5 更新商铺信息

```
PUT /shop
```

请求体同新增 Shop 对象，**必须带 id 字段**。

---

### 2.6 查询商铺类型列表

```
GET /shop-type/list
```

按 `sort` 字段升序返回全部商铺类型。用于首页类型下拉。公开接口，无需鉴权。

**返回 data 示例：**

```json
[
  {"id": 1, "name": "美食", "icon": "😋", "sort": 1},
  {"id": 2, "name": "休闲娱乐", "icon": "🎮", "sort": 2}
]
```

---

## shop-seckill 秒杀服务 (:8081)

> Redis Lua 原子预扣、一人一单、RocketMQ 事务消息削峰、库存双重回补（DB+Redis）
> Controller: `VoucherController`, `VoucherOrderController`

### 3.1 新增普通优惠券

```
POST /voucher
```

**请求体 (Voucher)：**

```json
{
  "shopId": 1,
  "title": "满100减20",
  "subTitle": "全店通用",
  "rules": "每人限领1张",
  "payValue": 0,
  "actualValue": 20,
  "type": 1,
  "status": 1,
  "stock": 100,
  "beginTime": "2026-11-01T00:00:00",
  "endTime": "2026-12-31T23:59:59"
}
```

成功后 `data` 为新券自增 ID。

**字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| type | int | 0=普通券（前端领后核销），1=秒杀券（走 seckill 接口）|
| stock | int | 总库存数 |
| beginTime / endTime | datetime | 有效期 |

---

### 3.2 新增秒杀优惠券

```
POST /voucher/seckill
```

请求体同普通券，但 `type=2`。成功后同时创建 `tb_seckill_voucher` 关联记录。

---

### 3.3 查询商铺优惠券列表

```
GET /voucher/list/{shopId}
```

**路径参数：**

| 名称 | 类型 | 说明 |
|------|------|------|
| shopId | long | 商铺自增主键 |

---

### 3.4 预热秒杀库存到 Redis 

```
POST /voucher-order/warmup
```

调试用。**每次跑全链路前必须先调一次**。执行：
1. 把所有秒杀券的 DB 库存**覆盖写入** Redis `seckill:stock:{voucherId}`
2. 清掉所有秒杀券的一人一单 Redis Set `seckill:order:{voucherId}` —— 重置秒杀状态以便重新测试

**无需鉴权**（放行路径）。

**返回 data：** `"预热完成: 共 N 张券，重置了 M 个用户集合"`

---

### 3.5 秒杀下单 

```
POST /voucher-order/seckill/{id}
```

秒杀全链路入口，返回**秒杀订单 ID**（雪花 ID）。**必须登录**。

**限流**：1 秒内同一用户最多 10 次（`@RateLimit perUser=true`）。

**路径参数：**

| 名称 | 类型 | 说明 |
|------|------|------|
| id | long | 秒杀券 ID（如 10）|

**执行流程：**
1. Redis Lua 脚本原子预扣库存 + 一人一单 Set 去重
2. 发 RocketMQ 事务消息异步创建订单 + 调 shop-pay 创建支付单
3. 立即返回订单 ID（后续步骤异步完成）

| 成功 data | 失败 errorMsg |
|-----------|---------------|
| 雪花 ID (Long) | "库存不足" / "不能重复下单" / "秒杀还未开始" / "秒杀已结束" |

---

### 3.6 核销优惠券

```
POST /voucher-order/use/{orderId}
```

**必须登录，只能核销自己的订单**。

**执行步骤：**
1. 查订单是否存在 + 是否属于当前用户
2. 检查订单当前 status 必须为 2（已支付）
3. 乐观锁更新 `status=2 → 3(已核销)`，写入 useTime
4. 乐观锁失败返回 "核销失败，订单状态已变更"

---

### 3.7 取消秒杀订单

```
POST /voucher-order/cancel/{orderId}
```

**必须登录，只能取消自己的订单，只能取消 status=1（未支付）的订单**。

**执行步骤：**
1. Dubbo 查关联 PayResultDTO → 调 shop-pay `cancelPay()` 关闭 PENDING 支付单
2. 乐观锁更新订单 `1 → 4(已取消)`
3. **DB+Redis 双重库存回补**（即使乐观锁失败也回补——消费端可能已处理，做幂等保护）

---

## shop-pay 支付服务 (:8084)

> 5 态状态机（PENDING/PAYING/PAID/FAILED/REFUNDED）、Outbox 本地消息表 + RocketMQ
> Controller: `PayController`

### 4.1 发起支付

```
POST /pay/create
```

**请求体 (PayCreateDTO)：**

```json
{
  "orderNo": "65519642386177307",
  "userId": 1011,
  "amount": 1000,
  "channel": "SIMULATE",
  "expireSeconds": 1800
}
```

| 字段 | 类型 | 必选 | 说明 |
|------|------|------|------|
| orderNo | string | ✅ | 业务订单号/秒杀订单 ID |
| userId | long | ✅ | 支付用户 ID |
| amount | int | ✅ | 金额（单位：分），范围 1~100000000 |
| channel | string | ❌ | 渠道：SIMULATE（默认）/ WECHAT / ALIPAY |
| expireSeconds | int | ❌ | 过期秒数，默认 1800，范围 300~86400 |

**返回 PayResultDTO：**

```json
{
  "payNo": "PAY17935152164742821",
  "orderNo": "65519642386177307",
  "status": 0,
  "statusDesc": "待支付",
  "payUrl": "SIMULATE://pay/PAY17935152164742821",
  "expireTime": "2026-11-04T16:00:00",
  "payTime": null
}
```

> 秒杀场景下由 shop-seckill Dubbo 自动调用，前端一般不直接调。

---

### 4.2 渠道回调（模拟支付成功/失败）

```
POST /pay/callback
```

**⚠️ 安全约束**：请求必须带 Header `X-Callback-Secret`，值必须等于配置的 `pay.callback.secret`（默认 `shophub-callback-dev`），否则返回 401。

**请求体：**

```json
{
  "payNo": "PAY17935152164742821",
  "success": true,
  "callbackData": ""
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| payNo | string | 支付单号 |
| success | boolean | true=支付成功，false=支付失败 |
| callbackData | string | 原始回调报文（可选，存库备查）|

**成功后状态变更**：`PENDING(0) → PAID(2)`（success=true）或 `PENDING(0) → FAILED(3)`（success=false）。

**异步通知**：通过 Outbox 写本地消息表 → RocketMQ 发送 → shop-seckill 的 `PayResultConsumer` 消费：
- 支付成功：订单状态 1→2（已支付）
- 支付失败：订单状态 1→4（已取消）+ Redis 库存回补

---

### 4.3 按支付单号查询

```
GET /pay/query/{payNo}
```

**路径参数：**

| 名称 | 类型 | 说明 |
|------|------|------|
| payNo | string | 支付单号，如 `PAY17935152164742821` |

---

### 4.4 按业务订单号查询

```
GET /pay/query/order/{orderNo}
```

查询关联的支付单详情。秒杀场景下 `orderNo` 就是秒杀订单的雪花 ID（转为 String）。

---

### 4.5 取消支付单

```
POST /pay/cancel
```

**请求体：**

```json
{
  "payNo": "PAY17935152164742821",
  "reason": "USER_CANCEL"
}
```

**约束**：只能取消 `status=0（PENDING）` 的支付单。

**执行流程**：
1. 更新支付单 `status=0 → FAILED(3)`
2. Outbox 写本地消息表 + RocketMQ 发 PAY_FAIL 消息
3. shop-seckill 消费端收到后自动：订单 1→4 + DB+Redis 双重库存回补

> shop-seckill 的 `cancel/{orderId}` 内部也会 Dubbo 调本接口，属于同一套逻辑。

---

### 4.6 退款

```
POST /pay/refund
```

**请求体：**

```json
{
  "payNo": "PAY17935152164742821",
  "refundAmount": 1000,
  "reason": "用户申请退款"
}
```

| 字段 | 类型 | 必选 | 说明 |
|------|------|------|------|
| payNo | string | ✅ | 支付单号 |
| refundAmount | int | ❌ | 退款金额（分），为空则全额退 |
| reason | string | ❌ | 退款原因 |

**约束**：只能退 `status=2（PAID）` 的支付单。

**状态变更**：`PAID(2) → REFUNDED(4)`

**异步通知**：Outbox + RocketMQ → shop-seckill 消费 → DB+Redis 双重库存回补（给下一次秒杀用）

---

## Redis Key 一览

| Key 模板 | 数据结构 | 所属服务 | TTL | 说明 |
|----------|----------|----------|-----|------|
| `login:code:{phone}` | String | shop-user | 2 分钟 | 登录验证码 |
| `login:token:{uuid}` | Hash(id/nickName/icon) | shop-user | 30 分钟 | 登录态，滑动续期 |
| `seckill:stock:{voucherId}` | String | shop-seckill | — | 秒杀 Redis 库存，warmup 时覆盖写 |
| `seckill:order:{voucherId}` | Set(userId) | shop-seckill | — | 一人一单去重 Set，warmup 时清空 |
| `cache:shop:{id}` | String(JSON) | shop-shop | 30 分钟 | 商铺详情缓存（Caffeine L1 miss 后查 Redis） |
| `shop-type:list` | String(JSON) | shop-shop | — | 商铺类型列表缓存 |

---

## 秒杀订单状态机

```
  1(待支付) ──支付成功──→ 2(已支付) ──核销──→ 3(已核销)
     │                        │
     ├──用户取消──→ 4(已取消)   └──退款──→ 4(已取消) + 库存回补
     │
     └──支付失败──→ 4(已取消) + 库存回补

取消 / 退款 / 支付失败 三条路径都会触发 shop-seckill 的 PayResultConsumer：
  • Redis incrby stock +1 + srem userId（立即回补）
  • DB SET stock = stock + 1（异步回补）
```

| status | 含义 | 触发操作 |
|--------|------|----------|
| 0 | 初始化（过渡态） | — |
| 1 | 待支付 | seckill 成功后初始状态 |
| 2 | 已支付 | 回调 success=true |
| 3 | 已核销 | 用户核销 use/{orderId} |
| 4 | 已取消 | cancel/{orderId} / 支付失败 / 退款 |

---

## 支付单状态机

```
  PENDING(0) ──success callback──→ PAID(2)
       │                                │
       ├──cancelPay()──→ FAILED(3)       └──refundPay()──→ REFUNDED(4)
       │
       └──success=false callback──→ FAILED(3)
```

| status | 含义 | 触发操作 |
|--------|------|----------|
| 0 | PENDING 待支付 | createPay 创建时初始状态 |
| 1 | PAYING 处理中 | 预留给真实渠道异步支付（当前模拟渠道跳过） |
| 2 | PAID 已支付 | callback success=true |
| 3 | FAILED 失败 | cancelPay / callback success=false |
| 4 | REFUNDED 已退款 | refundPay |

---

## 附录：异常错误码

| HTTP | 触发位置 | errorMsg | 说明 |
|------|----------|----------|------|
| 200 | shop-user/sendCode | 手机号格式错误！ | phone 不符合正则 |
| 200 | shop-user/login | 验证码错误 | Redis 无此 code / code 不匹配 |
| 200 | shop-seckill/seckill | 库存不足 | Redis Lua 检查失败 |
| 200 | shop-seckill/seckill | 不能重复下单 | Set 里已有当前 userId |
| 200 | shop-seckill/cancel | 无权取消该订单 | 订单不属于当前用户 |
| 200 | shop-seckill/cancel | 订单当前状态不允许取消 | status ≠ 1 |
| 200 | shop-seckill/use | 订单当前状态无法核销 | status ≠ 2 |
| 401 | LoginInterceptor | — | Token 无效 / 过期 / 缺失 |
| 401 | shop-pay/callback | Unauthorized callback | X-Callback-Secret 不匹配 |
| 400 | shop-pay/refund | payNo 不能为空 | 请求体缺失字段 |
