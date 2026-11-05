package com.shophub;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.shophub.mapper")
@SpringBootApplication
public class ShopShopApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShopShopApplication.class, args);
    }
}
