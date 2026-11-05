package com.shophub.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI shopShopOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ShopHub 店汇通 — 商铺服务 API")
                        .version("1.0.0")
                        .description("ShopHub 店汇通 商铺微服务：商铺浏览、类型下拉、Caffeine→Redis→MySQL 三级缓存、延迟双删")
                        .contact(new Contact()
                                .name("ShopHub 店汇通")
                                .email("dev@shophub.com")));
    }
}
