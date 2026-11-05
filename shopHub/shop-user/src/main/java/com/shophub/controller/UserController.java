package com.shophub.controller;


import cn.hutool.core.bean.BeanUtil;
import com.shophub.annotation.RateLimit;
import com.shophub.dto.LoginFormDTO;
import com.shophub.dto.Result;
import com.shophub.dto.UserDTO;
import com.shophub.entity.User;
import com.shophub.entity.UserInfo;
import com.shophub.service.IUserInfoService;
import com.shophub.service.IUserService;
import com.shophub.utils.UserHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

@Tag(name = "用户服务", description = "ShopHub・店汇通 用户注册/登录/信息查询。登录用手机号+验证码（Redis 存储，2 分钟有效）。")
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    @Operation(summary = "发送手机验证码", description = "按 IP 限流 60 秒 5 次。验证码存 Redis key=login:code:{phone}，2 分钟有效")
    @RateLimit(windowSeconds = 60, limit = 5, perUser = false)
    @PostMapping("code")
    public Result sendCode(@Parameter(description = "手机号") @RequestParam("phone") String phone, HttpSession session) {
        // 发送短信验证码并保存验证码
        return userService.sendCode(phone, session);
    }

    @Operation(summary = "用户登录", description = "手机号+验证码 或 手机号+密码。首次登录自动注册。返回 token (authorization Header)")
    @RateLimit(windowSeconds = 60, limit = 10, perUser = false)
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){
        // 实现登录功能
        return userService.login(loginForm, session);
    }

    @Operation(summary = "获取当前登录用户")
    @GetMapping("/me")
    public Result me(){
        // 获取当前登录的用户并返回
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    @Operation(summary = "查询用户详情")
    @GetMapping("/info/{id}")
    public Result info(@Parameter(description = "用户ID") @PathVariable("id") Long userId){
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }

    @Operation(summary = "按ID查询用户")
    @GetMapping("/{id:\\d+}")
    public Result queryUserById(@Parameter(description = "用户ID") @PathVariable("id") Long userId){
        // 查询详情
        User user = userService.getById(userId);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 返回
        return Result.ok(userDTO);
    }

}