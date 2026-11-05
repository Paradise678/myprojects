package com.shophub.controller;


import com.shophub.dto.Result;
import com.shophub.entity.Voucher;
import com.shophub.service.IVoucherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@Tag(name = "优惠券", description = "ShopHub・店汇通 优惠券管理：新增普通券、秒杀券、查询商铺优惠券列表")
@RestController
@RequestMapping("/voucher")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;

    @Operation(summary = "新增普通优惠券")
    @PostMapping
    public Result addVoucher(@RequestBody Voucher voucher) {
        voucherService.save(voucher);
        return Result.ok(voucher.getId());
    }

    @Operation(summary = "新增秒杀优惠券")
    @PostMapping("seckill")
    public Result addSeckillVoucher(@RequestBody Voucher voucher) {
        voucherService.addSeckillVoucher(voucher);
        return Result.ok(voucher.getId());
    }

    @Operation(summary = "查询商铺优惠券列表")
    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(@Parameter(description = "商铺ID") @PathVariable("shopId") Long shopId) {
       return voucherService.queryVoucherOfShop(shopId);
    }
}
