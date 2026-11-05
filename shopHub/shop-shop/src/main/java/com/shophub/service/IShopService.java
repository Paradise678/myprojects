package com.shophub.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.shophub.dto.Result;
import com.shophub.entity.Shop;

public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result update(Shop shop);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
