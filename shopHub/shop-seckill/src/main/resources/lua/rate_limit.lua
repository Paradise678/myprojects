-- 滑动窗口限流（ZSet 实现）
-- KEYS[1] 限流 key，例如 rl:VoucherOrderController.seckillVoucher:u123
-- ARGV[1] 当前时间戳（毫秒）
-- ARGV[2] 窗口大小（毫秒）
-- ARGV[3] 窗口内允许的最大请求数
-- ARGV[4] 本次请求的唯一 member（时间戳 + UUID，避免同一毫秒重复）
--
-- 返回 1：放行；返回 0：限流

-- 1. 移除窗口外的历史请求记录
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1] - ARGV[2])

-- 2. 统计当前窗口内的请求数
local count = redis.call('ZCARD', KEYS[1])

-- 3. 未达到阈值则记录本次请求并放行
if count < tonumber(ARGV[3]) then
    redis.call('ZADD', KEYS[1], ARGV[1], ARGV[4])
    -- 兜底过期，避免 key 永久驻留
    redis.call('PEXPIRE', KEYS[1], ARGV[2])
    return 1
end

return 0
