package com.dmitrymrsh.bunsan.demo.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Простой REST-сервис, запускаемый в нескольких инстансах ({@code demo-service-1..4}).
 *
 * <p>bunsan-spring-boot-starter автоматически регистрирует:
 * <ul>
 *   <li>{@code OverloadHealthIndicator} — переводит инстанс в {@code OUT_OF_SERVICE}
 *       при превышении порогов CPU или heap;</li>
 *   <li>{@code LbHealthEndpoint} — эндпоинт {@code /actuator/lb-health}
 *       с JSON-сводкой метрик для {@code AdaptiveLoadBalancer}.</li>
 * </ul>
 *
 * <p>Инстанс с переменной окружения {@code STRESS_ENABLED=true} имитирует деградацию
 * через CPU-нагрузку посредством {@link LoadSimulator}.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class DemoServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoServiceApplication.class, args);
    }
}
