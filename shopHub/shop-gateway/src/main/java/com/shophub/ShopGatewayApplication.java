package com.shophub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * shop-gateway 统一 API 网关启动类。
 *
 * <p>职责：路由转发 + CORS 跨域 + 鉴权前置（后续可扩展）。</p>
 * <p>纯 WebFlux（Netty）项目，不依赖 Servlet 容器。</p>
 *
 * <h3>路由规则</h3>
 * <pre>
 *  http://localhost:8080/shop/**        → shop-shop   :8082
 *  http://localhost:8080/shop-type/**   → shop-shop   :8082
 *  http://localhost:8080/user/**        → shop-user   :8083
 *  http://localhost:8080/voucher/**     → shop-seckill:8081
 *  http://localhost:8080/voucher-order/** → shop-seckill:8081
 *  http://localhost:8080/seckill/**     → shop-seckill:8081
 *  http://localhost:8080/pay/**         → shop-pay    :8084
 *  http://localhost:8080/blog/**        → shop-shop   :8082 （如果博客在 shop-shop）
 *  http://localhost:8080/actuator/**    → 网关自己的健康检查/路由信息
 * </pre>
 */
@SpringBootApplication
public class ShopGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShopGatewayApplication.class, args);
        System.out.println();
        System.out.println("==============================================");
        System.out.println("  shop-gateway 已启动 → http://localhost:8080");
        System.out.println("  路由端点: http://localhost:8080/actuator/gateway/routes");
        System.out.println("==============================================");
    }
}
