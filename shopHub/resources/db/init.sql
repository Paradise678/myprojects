-- ============================================================
-- ShopHub 微服务架构 一键初始化脚本
-- 包含所有表结构（ddl）+ 必需索引 + 幂等消息日志表
-- 导入顺序: 直接 source 这一个文件即可
-- 使用:  mysql -uroot -p840614 < init.sql
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ------------------------------------------------------------
-- 1. 创建两个库
-- ------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS shophub      DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS shophub_pay  DEFAULT CHARACTER SET utf8mb4;

-- ============================================================
-- PART A: shophub 库 (shop-user / shop-shop / shop-seckill 共用)
-- ============================================================
USE shophub;

-- ------------------------------------------------------------
-- A1. tb_user — 用户基础 (shop-user)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS tb_user;
CREATE TABLE tb_user (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  phone       VARCHAR(11)     NOT NULL COMMENT '手机号码',
  password    VARCHAR(128)    NULL     DEFAULT '' COMMENT '密码，加密存储',
  nick_name   VARCHAR(32)     NULL     DEFAULT '' COMMENT '昵称，默认是用户id',
  icon        VARCHAR(255)    NULL     DEFAULT '' COMMENT '人物头像',
  create_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE INDEX uniqe_key_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';

-- ------------------------------------------------------------
-- A2. tb_user_info — 用户详情 (shop-user)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS tb_user_info;
CREATE TABLE tb_user_info (
  user_id     BIGINT UNSIGNED NOT NULL COMMENT '主键，用户id',
  city        VARCHAR(64)     NULL     DEFAULT '' COMMENT '城市名称',
  introduce   VARCHAR(128)    NULL     DEFAULT NULL COMMENT '个人介绍',
  fans        INT UNSIGNED    NULL     DEFAULT 0 COMMENT '粉丝数量',
  followee    INT UNSIGNED    NULL     DEFAULT 0 COMMENT '关注的人的数量',
  gender      TINYINT UNSIGNED NULL    DEFAULT 0 COMMENT '性别，0：男，1：女',
  birthday    DATE            NULL     DEFAULT NULL COMMENT '生日',
  credits     INT UNSIGNED    NULL     DEFAULT 0 COMMENT '积分',
  level       TINYINT UNSIGNED NULL    DEFAULT 0 COMMENT '会员级别，0~9级,0代表未开通会员',
  create_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户详情';

-- ------------------------------------------------------------
-- A3. tb_shop_type — 商铺类型 (shop-shop)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS tb_shop_type;
CREATE TABLE tb_shop_type (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  name        VARCHAR(32)     NULL     DEFAULT NULL COMMENT '类型名称',
  icon        VARCHAR(255)    NULL     DEFAULT NULL COMMENT '图标',
  sort        INT UNSIGNED    NULL     DEFAULT NULL COMMENT '顺序',
  create_time TIMESTAMP       NULL     DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP       NULL     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商铺类型';

-- ------------------------------------------------------------
-- A4. tb_shop — 商铺 (shop-shop)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS tb_shop;
CREATE TABLE tb_shop (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  name        VARCHAR(128)    NOT NULL COMMENT '商铺名称',
  type_id     BIGINT UNSIGNED NOT NULL COMMENT '商铺类型的id',
  images      VARCHAR(1024)   NOT NULL COMMENT '商铺图片，多个图片以,隔开',
  area        VARCHAR(128)    NULL     DEFAULT NULL COMMENT '商圈，例如陆家嘴',
  address     VARCHAR(255)    NOT NULL COMMENT '地址',
  x           DOUBLE UNSIGNED NOT NULL COMMENT '经度',
  y           DOUBLE UNSIGNED NOT NULL COMMENT '纬度',
  avg_price   BIGINT UNSIGNED NULL     DEFAULT NULL COMMENT '均价，取整数',
  sold        INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '销量',
  comments    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '评论数量',
  score       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '评分×10（如 47 = 4.7）',
  open_hours  VARCHAR(32)     NULL     DEFAULT NULL COMMENT '营业时间，例如 10:00-22:00',
  create_time TIMESTAMP       NULL     DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP       NULL     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  INDEX foreign_key_type (type_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商铺';

-- ------------------------------------------------------------
-- A5. tb_voucher — 优惠券 (shop-seckill)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS tb_voucher;
CREATE TABLE tb_voucher (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  shop_id      BIGINT UNSIGNED NULL     DEFAULT NULL COMMENT '商铺id',
  title        VARCHAR(255)    NOT NULL COMMENT '代金券标题',
  sub_title    VARCHAR(255)    NULL     DEFAULT NULL COMMENT '副标题',
  rules        VARCHAR(1024)   NULL     DEFAULT NULL COMMENT '使用规则',
  pay_value    BIGINT UNSIGNED NOT NULL COMMENT '支付金额（分）',
  actual_value BIGINT          NOT NULL COMMENT '抵扣金额（分）',
  type         TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0:普通券  1:秒杀券',
  status       TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1:上架  2:下架  3:过期',
  create_time  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券';

-- ------------------------------------------------------------
-- A6. tb_seckill_voucher — 秒杀券扩展 (shop-seckill)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS tb_seckill_voucher;
CREATE TABLE tb_seckill_voucher (
  voucher_id  BIGINT UNSIGNED NOT NULL COMMENT '关联的优惠券的id',
  stock       INT             NOT NULL COMMENT '库存',
  create_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  begin_time  TIMESTAMP       NOT NULL DEFAULT '1970-01-01 08:00:01' COMMENT '生效时间',
  end_time    TIMESTAMP       NOT NULL DEFAULT '1970-01-01 08:00:01' COMMENT '失效时间',
  update_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (voucher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀优惠券表，与优惠券是一对一关系';

-- ------------------------------------------------------------
-- A7. tb_voucher_order — 秒杀订单 (shop-seckill)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS tb_voucher_order;
CREATE TABLE tb_voucher_order (
  id          BIGINT          NOT NULL COMMENT '主键（雪花ID）',
  user_id     BIGINT UNSIGNED NOT NULL COMMENT '下单用户',
  voucher_id  BIGINT UNSIGNED NOT NULL COMMENT '优惠券',
  pay_type    TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '支付方式：1:余额  2:支付宝  3:微信',
  status      TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '订单状态：1未支付 2已支付 3已核销 4已取消 5退款中 6已退款',
  create_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  pay_time    TIMESTAMP       NULL     DEFAULT NULL COMMENT '支付时间',
  use_time    TIMESTAMP       NULL     DEFAULT NULL COMMENT '核销时间',
  refund_time TIMESTAMP       NULL     DEFAULT NULL COMMENT '退款时间',
  update_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_voucher (user_id, voucher_id) COMMENT '一人一单核心索引（兜底 Redis Lua）',
  KEY idx_voucher_id (voucher_id) COMMENT '对账按 voucher_id 统计订单'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀订单';

-- ------------------------------------------------------------
-- A8. tb_seckill_message_log — MQ 消费日志 (shop-seckill)
--     RocketMQ 事务消息消费者幂等保证
-- ------------------------------------------------------------
DROP TABLE IF EXISTS tb_seckill_message_log;
CREATE TABLE tb_seckill_message_log (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  order_id     BIGINT       NOT NULL COMMENT '订单ID',
  user_id      BIGINT       NOT NULL COMMENT '用户ID',
  voucher_id   BIGINT       NOT NULL COMMENT '优惠券ID',
  status       TINYINT      NOT NULL DEFAULT 0 COMMENT '消费状态：0处理中  1已消费  2已入死信',
  retry_count  INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
  error_msg    VARCHAR(500) NULL     DEFAULT NULL COMMENT '错误信息',
  create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_id (order_id),
  KEY idx_voucher_id (voucher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀消息消费日志（幂等保证）';

-- ------------------------------------------------------------
-- A9. tb_blog / tb_blog_comments / tb_follow / tb_sign — 社交（hmdp 遗留）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS tb_blog;
CREATE TABLE tb_blog (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  shop_id     BIGINT          NOT NULL COMMENT '商户id',
  user_id     BIGINT UNSIGNED NOT NULL COMMENT '用户id',
  title       VARCHAR(255)    NOT NULL COMMENT '标题',
  images      VARCHAR(2048)   NOT NULL COMMENT '探店的照片，最多9张，多张以,隔开',
  content     VARCHAR(2048)   NOT NULL COMMENT '探店的文字描述',
  liked       INT UNSIGNED    NULL     DEFAULT 0 COMMENT '点赞数量',
  comments    INT UNSIGNED    NULL     DEFAULT NULL COMMENT '评论数量',
  create_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='探店';

DROP TABLE IF EXISTS tb_blog_comments;
CREATE TABLE tb_blog_comments (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id     BIGINT UNSIGNED NOT NULL COMMENT '用户id',
  blog_id     BIGINT UNSIGNED NOT NULL COMMENT '探店id',
  parent_id   BIGINT UNSIGNED NOT NULL COMMENT '关联的1级评论id，如果是一级评论，则值为0',
  answer_id   BIGINT UNSIGNED NOT NULL COMMENT '回复的评论id',
  content     VARCHAR(255)    NOT NULL COMMENT '回复的内容',
  liked       INT UNSIGNED    NULL     DEFAULT NULL COMMENT '点赞数',
  status      TINYINT UNSIGNED NULL    DEFAULT NULL COMMENT '状态，0正常，1被举报，2禁止查看',
  create_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='探店评论';

DROP TABLE IF EXISTS tb_follow;
CREATE TABLE tb_follow (
  id          BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id     BIGINT UNSIGNED NOT NULL COMMENT '用户id',
  follow_user_id BIGINT UNSIGNED NOT NULL COMMENT '被关注的用户id',
  create_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注';

DROP TABLE IF EXISTS tb_sign;
CREATE TABLE tb_sign (
  id       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id  BIGINT UNSIGNED NOT NULL COMMENT '用户id',
  year     YEAR            NOT NULL COMMENT '签到的年',
  month    TINYINT         NOT NULL COMMENT '签到的月',
  date     DATE            NOT NULL COMMENT '签到的日期',
  is_backup TINYINT UNSIGNED NULL   DEFAULT NULL COMMENT '是否补签',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='签到';

-- ============================================================
-- PART B: shophub_pay 库 (shop-pay)
-- ============================================================
USE shophub_pay;

-- ------------------------------------------------------------
-- B1. tb_pay_order — 支付订单
-- ------------------------------------------------------------
DROP TABLE IF EXISTS tb_pay_order;
CREATE TABLE tb_pay_order (
  id              BIGINT       NOT NULL COMMENT '主键 (雪花ID)',
  order_no        VARCHAR(64)  NOT NULL COMMENT '业务订单号 (关联秒杀订单)',
  pay_no          VARCHAR(64)  NOT NULL COMMENT '支付单号 (支付侧唯一标识)',
  user_id         BIGINT       NOT NULL COMMENT '支付用户ID',
  amount          INT          NOT NULL COMMENT '支付金额 (单位: 分)',
  channel         VARCHAR(32)  NOT NULL COMMENT '支付渠道: WECHAT/ALIPAY/SIMULATE',
  status          TINYINT      NOT NULL DEFAULT 0 COMMENT '状态: 0-待支付 1-支付中 2-已支付 3-支付失败 4-已退款',
  callback_data   TEXT                  COMMENT '渠道回调原始报文',
  callback_hash   VARCHAR(64)  NULL DEFAULT NULL COMMENT 'SHA256 回调报文 hash (防重复回调幂等)',
  pay_time        DATETIME             COMMENT '支付成功时间',
  expire_time     DATETIME     NOT NULL COMMENT '支付过期时间',
  refund_time     DATETIME             COMMENT '退款时间',
  refund_amount   INT                  COMMENT '退款金额 (分)',
  create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_no (pay_no),
  UNIQUE KEY uk_order_no (order_no),
  UNIQUE KEY uk_callback_hash (callback_hash),
  KEY idx_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付订单表';

-- ------------------------------------------------------------
-- B2. tb_pay_message_temp — Outbox 本地消息表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS tb_pay_message_temp;
CREATE TABLE tb_pay_message_temp (
  id              BIGINT       NOT NULL COMMENT '主键 (雪花ID)',
  msg_type        VARCHAR(64)  NOT NULL COMMENT '消息类型: PAY_SUCCESS/PAY_FAIL/REFUND',
  biz_key         VARCHAR(128) NOT NULL COMMENT '业务标识 (支付单号)',
  msg_body        TEXT         NOT NULL COMMENT '消息体 JSON',
  mq_topic        VARCHAR(64)  NOT NULL COMMENT 'RocketMQ Topic',
  mq_tag          VARCHAR(32)           COMMENT 'RocketMQ Tag',
  status          TINYINT      NOT NULL DEFAULT 0 COMMENT '状态: 0-待发送 1-已发送 2-发送失败',
  retry_count     INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
  next_retry_time DATETIME             COMMENT '下次重试时间',
  create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_status_retry (status, next_retry_time),
  KEY idx_biz_key (biz_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表 (Outbox)';

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 导入后快速验证:
--   mysql -uroot -p840614 -e "SHOW DATABASES LIKE 'shophub%';"
--   mysql -uroot -p840614 -e "USE shophub; SHOW TABLES;"
--   mysql -uroot -p840614 -e "USE shophub_pay; SHOW TABLES;"
--   mysql -uroot -p840614 -e "SHOW INDEX FROM shophub.tb_voucher_order;"
--   mysql -uroot -p840614 -e "DESC shophub_pay.tb_pay_order;"
-- ============================================================
