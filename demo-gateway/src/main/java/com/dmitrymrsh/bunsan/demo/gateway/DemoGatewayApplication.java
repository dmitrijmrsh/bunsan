package com.dmitrymrsh.bunsan.demo.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Spring Cloud Gateway — точка входа для клиентских запросов.
 *
 * <p>Маршрутизирует запросы {@code /api/**} к {@code demo-service} через
 * схему {@code lb://demo-service}, что активирует Spring Cloud LoadBalancer.
 * bunsan-spring-boot-starter автоматически подменяет стандартный RoundRobin
 * на алгоритм, заданный в {@code bunsan.algorithm}.
 *
 * <p>Стартер также регистрирует:
 * <ul>
 *   <li>{@code LatencyTrackingGlobalFilter} — замер latency и декремент in-flight;</li>
 *   <li>{@code LoadBalancerMetrics} — публикация кастомных метрик в Prometheus.</li>
 * </ul>
 */
@SpringBootApplication
@EnableDiscoveryClient
public class DemoGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoGatewayApplication.class, args);
    }
}
