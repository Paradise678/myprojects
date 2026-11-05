package com.shophub;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * shop-user 用户微服务启动类
 * 端口: 8083
 * 职责: 用户登录验证码 + 登录态 + 用户信息
 * Dubbo Provider: IUserService, IUserInfoService
 */
@EnableDubbo
@MapperScan("com.shophub.mapper")
@SpringBootApplication
public class ShopUserApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShopUserApplication.class, args);
    }
}
