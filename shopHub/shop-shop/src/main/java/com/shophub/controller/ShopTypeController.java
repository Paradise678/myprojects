package com.shophub.controller;

import com.shophub.dto.Result;
import com.shophub.service.IShopTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@Tag(name = "商铺类型")
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {

    @Resource
    private IShopTypeService typeService;

    @Operation(summary = "查询商铺类型列表")
    @GetMapping("list")
    public Result queryTypeList() {
        return Result.ok(typeService.query()
                .orderByAsc("sort")
                .list());
    }
}
