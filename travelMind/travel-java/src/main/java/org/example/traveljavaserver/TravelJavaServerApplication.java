package org.example.traveljavaserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 应用启动类
 * @SpringBootApplication 是一个组合注解，包含：
 *   - @Configuration：标记为配置类
 *   - @EnableAutoConfiguration：开启自动配置（根据依赖自动装配 Bean）
 *   - @ComponentScan：自动扫描当前包及子包下的组件（@Controller/@Service/@Component 等）
 *
 * 整个后端服务的入口，运行 main 方法即启动内嵌 Tomcat，开始监听端口。
 */
@SpringBootApplication
public class TravelJavaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TravelJavaServerApplication.class, args);
    }

}
