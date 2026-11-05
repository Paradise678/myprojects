# ============================================================
# ShopHub 应用镜像
# 前置：先在 Windows 本机执行 mvn clean package -DskipTests
# 生成 shop-user/target/shop-user.jar 等 5 个 fat jar
# ============================================================

FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app
ENV TZ=Asia/Shanghai

# ---- shop-user (:8083) ----
FROM runtime AS shop-user
COPY shop-user/target/shop-user.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]

# ---- shop-shop (:8082) ----
FROM runtime AS shop-shop
COPY shop-shop/target/shop-shop.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]

# ---- shop-pay (:8084) ----
FROM runtime AS shop-pay
COPY shop-pay/target/shop-pay.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]

# ---- shop-seckill (:8081) ----
FROM runtime AS shop-seckill
COPY shop-seckill/target/shop-seckill.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]

# ---- shop-gateway (:8080) ----
FROM runtime AS shop-gateway
COPY shop-gateway/target/shop-gateway.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
