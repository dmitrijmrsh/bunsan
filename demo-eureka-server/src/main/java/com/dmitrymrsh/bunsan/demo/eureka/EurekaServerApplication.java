package com.dmitrymrsh.bunsan.demo.eureka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Eureka Server с подключённым bunsan-spring-boot-starter.
 *
 * <p>Стартер автоматически регистрирует {@code OverloadInstanceFilter} —
 * AOP-аспект, перехватывающий вызовы {@code PeerAwareInstanceRegistry.getApplications()}
 * и исключающий из ответа инстансы со статусом {@code OUT_OF_SERVICE}.
 *
 * <p>Конфигурация фильтрации задаётся в {@code application.yml}:
 * <pre>{@code
 * bunsan.discovery.overload-filter.enabled=true
 * }</pre>
 */
@SpringBootApplication
@EnableEurekaServer
public class EurekaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }
}
