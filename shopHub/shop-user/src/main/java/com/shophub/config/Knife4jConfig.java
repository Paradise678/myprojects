package com.shophub.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI shopUserOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ShopHub・店汇通 — 用户服务 API")
                        .version("1.0.0")
                        .description("ShopHub・店汇通 用户微服务：短信验证码登录、Token 鉴权、签到、用户资料")
                        .contact(new Contact().name("ShopHub・店汇通").email("dev@shophub.com")));
    }
}
