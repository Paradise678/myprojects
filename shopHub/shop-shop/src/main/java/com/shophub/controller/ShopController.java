package com.shophub.controller;

import com.shophub.dto.Result;
import com.shophub.entity.Shop;
import com.shophub.service.IShopService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@Tag(name = "商铺管理")
@RestController
@RequestMapping("/shop")
public class ShopController {

    @Resource
    public IShopService shopService;

    @Operation(summary = "按ID查询商铺详情")
    @GetMapping("/{id}")
    public Result queryShopById(@PathVariable("id") Long id) {
        return shopService.queryById(id);
    }

    @Operation(summary = "新增商铺")
    @PostMapping
    public Result saveShop(@RequestBody Shop shop) {
        shopService.save(shop);
        return Result.ok(shop.getId());
    }

    @Operation(summary = "更新商铺信息")
    @PutMapping
    public Result updateShop(@RequestBody Shop shop) {
        return shopService.update(shop);
    }

    @Operation(summary = "按类型分页查询商铺")
    @GetMapping("/of/type")
    public Result queryShopByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "x", required = false) Double x,
            @RequestParam(value = "y", required = false) Double y) {
        return shopService.queryShopByType(typeId, current, x, y);
    }

    @Operation(summary = "按名称关键字分页查询商铺")
    @GetMapping("/of/name")
    public Result queryShopByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 这个方法直接在 Controller 层拼装查询（bytecode 显示没有委托给 Service）
        return Result.ok(shopService.query()
                .like(name != null, "name", name)
                .page(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current.longValue(), 10L))
                .getRecords());
    }
}
