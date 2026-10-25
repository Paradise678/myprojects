# ===== 构建阶段：Maven + JDK17 =====
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 阿里云 Maven 镜像，加速依赖下载
COPY settings-aliyun.xml /root/.m2/settings.xml

# 先复制 pom 下载依赖，利用 Docker 层缓存
COPY pom.xml .
RUN mvn dependency:go-offline -q

# 再复制源码打包
COPY src ./src
RUN mvn package -DskipTests -q

# ===== 运行阶段：轻量 JRE =====
FROM eclipse-temurin:17-jre
WORKDIR /app
ENV TZ=Asia/Shanghai
COPY --from=build /app/target/hm-dianping-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
