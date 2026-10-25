# ===== 构建阶段：Maven + JDK 17 编译打包 =====
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 使用阿里云 Maven 镜像加速依赖下载
COPY settings-aliyun.xml /root/.m2/settings.xml

# 先拷贝 pom 拉取依赖，利用 Docker 层缓存加速重复构建
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ===== 运行阶段：仅含 JRE 的轻量镜像 =====
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/travel-java-server-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 3200
ENTRYPOINT ["java", "-jar", "app.jar"]
