-- RocketMQ 版秒杀预扣脚本：只做 Redis 预检与预扣，不再 XADD 到 Stream
-- ARGV[1] 优惠券id
-- ARGV[2] 用户id
-- 返回 1：库存不足；2：重复下单；0：预扣成功

local voucherId = ARGV[1]
local userId = ARGV[2]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

local stock = redis.call('get', stockKey)
if (stock == false or tonumber(stock) <= 0) then
    return 1
end

if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
return 0
