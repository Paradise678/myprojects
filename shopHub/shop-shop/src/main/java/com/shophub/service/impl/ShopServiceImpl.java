package com.shophub.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shophub.dto.Result;
import com.shophub.entity.Shop;
import com.shophub.mapper.ShopMapper;
import com.shophub.service.IShopService;
import com.shophub.utils.CacheClient;
import com.shophub.utils.RedisConstants;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private static final long DELAYED_DELETE_MS = 500L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private Cache<Long, Shop> shopLocalCache;

    @Resource
    private ScheduledExecutorService cacheDeleteScheduler;

    @Override
    public Result queryById(Long id) {
        // 1. 查 Caffeine 本地缓存（L1）
        Shop shop = shopLocalCache.getIfPresent(id);
        if (shop == null) {
            // 2. 查 Redis + 穿透保护（L2），回源 DB
            shop = (Shop) cacheClient.queryWithPassThrough(
                    RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
                    this::getById,
                    RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
            // 3. 回填 L1
            if (shop != null) {
                shopLocalCache.put(id, shop);
            }
        }
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 先更新 DB
        updateById(shop);
        // 事务提交后立即删缓存 + 延迟双删
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictShopCache(id);
                cacheDeleteScheduler.schedule(() -> evictShopCache(id),
                        DELAYED_DELETE_MS, TimeUnit.MILLISECONDS);
            }
        });
        return Result.ok();
    }

    @Transactional
    public void evictShopCache(Long id) {
        shopLocalCache.invalidate(id);
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 没有坐标 → 普通分页
        if (x == null || y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, 5L));
            return Result.ok(page.getRecords());
        }

        // 有坐标 → Redis GEO 搜索 + 按距离排序
        int fromIndex = (current - 1) * 5;
        int toIndex = current * 5;

        String geoKey = RedisConstants.SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        geoKey,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                                .includeDistance()
                                .limit(toIndex)
                );

        if (results == null) {
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= fromIndex) {
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(fromIndex).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            distanceMap.put(shopIdStr, result.getDistance());
        });

        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list();

        for (Shop shop : shops) {
            Distance distance = distanceMap.get(shop.getId().toString());
            if (distance != null) {
                shop.setDistance(distance.getValue());
            }
        }
        return Result.ok(shops);
    }
}
