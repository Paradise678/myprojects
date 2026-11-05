package com.shophub.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shophub.entity.ShopType;
import com.shophub.mapper.ShopTypeMapper;
import com.shophub.service.IShopTypeService;
import org.springframework.stereotype.Service;

@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
}
