-- ============================================================
-- 方案 B：RocketMQ 事务消息 + 幂等 + 对账
-- 在 hmdp 库执行（MySQL 8.0）
-- ============================================================

-- 1. 清理历史重复订单（如果有），否则唯一索引会创建失败
DELETE o1 FROM tb_voucher_order o1
JOIN tb_voucher_order o2
  ON o1.user_id = o2.user_id
 AND o1.voucher_id = o2.voucher_id
 AND o1.id > o2.id;

-- 2. 幂等唯一索引：一个用户对一张券只能有一笔订单
--    如果索引已存在，跳过这一条
ALTER TABLE tb_voucher_order
  ADD UNIQUE KEY uk_user_voucher (user_id, voucher_id);

-- 3. 对账按 voucher_id 统计订单数
--    如果已有该索引，跳过这一条
ALTER TABLE tb_voucher_order
  ADD KEY idx_voucher_id (voucher_id);

-- 4. 消息消费日志：唯一索引保证消费者幂等
CREATE TABLE IF NOT EXISTS tb_seckill_message_log (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  order_id BIGINT NOT NULL COMMENT '订单ID',
  user_id BIGINT NOT NULL COMMENT '用户ID',
  voucher_id BIGINT NOT NULL COMMENT '优惠券ID',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '消费状态：0处理中，1已消费，2已入死信',
  retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数',
  error_msg VARCHAR(500) DEFAULT NULL COMMENT '错误信息',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_id (order_id),
  KEY idx_voucher_id (voucher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀消息消费日志';

-- 5. 如果表已按旧版本创建（status 注释只有 0/1），执行以下语句同步注释（可选）：
-- ALTER TABLE tb_seckill_message_log
--   MODIFY COLUMN status TINYINT NOT NULL DEFAULT 0 COMMENT '消费状态：0处理中，1已消费，2已入死信';
