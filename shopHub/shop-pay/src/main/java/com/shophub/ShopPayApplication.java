package com.shophub;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * shop-pay 支付微服务启动类
 *
 * @EnableDubbo   —— 启用 Dubbo Provider 自动导出
 * @EnableScheduling —— 启用 Outbox 定时扫描
 */
@EnableDubbo
@EnableScheduling
@SpringBootApplication
public class ShopPayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShopPayApplication.class, args);
    }
}
