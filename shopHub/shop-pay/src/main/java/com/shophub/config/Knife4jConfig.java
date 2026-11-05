package com.shophub.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI shopPayOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ShopHub・店汇通 — 支付服务 API")
                        .version("1.0.0")
                        .description("ShopHub・店汇通 支付微服务：5 态状态机（PENDING/PAYING/PAID/FAILED/REFUNDED）、渠道 SPI 扩展、Outbox 本地消息表")
                        .contact(new Contact().name("ShopHub・店汇通").email("dev@shophub.com")));
    }
}
