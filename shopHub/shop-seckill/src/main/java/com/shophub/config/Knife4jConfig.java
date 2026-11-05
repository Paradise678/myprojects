package com.shophub.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI shopSeckillOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ShopHub・店汇通 — 秒杀服务 API")
                        .version("1.0.0")
                        .description("ShopHub・店汇通 秒杀微服务：Redis Lua 原子预扣、一人一单、RocketMQ 事务消息削峰、库存双重回补（DB+Redis）")
                        .contact(new Contact().name("ShopHub・店汇通").email("dev@shophub.com")));
    }
}
