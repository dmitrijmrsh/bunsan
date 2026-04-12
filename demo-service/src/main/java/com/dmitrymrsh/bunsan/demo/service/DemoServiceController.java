package com.dmitrymrsh.bunsan.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST-контроллер демо-сервиса.
 *
 * <p>Предоставляет три эндпоинта для нагрузочного тестирования:
 * <ul>
 *   <li>{@code GET /api/hello} — быстрый ответ с идентификатором инстанса;</li>
 *   <li>{@code GET /api/delay/{ms}} — искусственная задержка (виртуальный поток);</li>
 *   <li>{@code GET /api/cpu/{iterations}} — CPU-нагрузка (вычисление sqrt).</li>
 * </ul>
 *
 * <p>Идентификатор инстанса ({@code instanceId}) позволяет Gatling-симуляциям
 * и Python-скриптам определять, на какой инстанс пришёл конкретный запрос.
 */
@RestController
@RequestMapping("/api")
public class DemoServiceController {

    private static final Logger log = LoggerFactory.getLogger(DemoServiceController.class);

    private final String instanceId;
    private final int stressDelayMs;

    public DemoServiceController(
        @Value("${spring.application.name}") String appName,
        @Value("${server.port}") int port,
        @Value("${STRESS_DELAY_MS:0}") int stressDelayMs
    ) {
        this.instanceId = appName + ":" + port;
        this.stressDelayMs = stressDelayMs;
    }

    /**
     * Быстрый ответ — основной эндпоинт для нагрузочных тестов.
     *
     * @return JSON с сообщением, ID инстанса и меткой времени
     */
    @GetMapping("/hello")
    public ResponseEntity<Map<String, Object>> hello() throws InterruptedException {
        if (stressDelayMs > 0) {
            Thread.sleep(stressDelayMs);
        }
        return ResponseEntity.ok(Map.of(
            "message",    "Hello from " + instanceId,
            "instanceId", instanceId,
            "timestamp",  System.currentTimeMillis()
        ));
    }

    /**
     * Искусственная задержка для симуляции медленного инстанса.
     *
     * <p>Использует {@link Thread#sleep(long)} — безопасно на виртуальных потоках
     * (Spring Boot 3.x с Tomcat virtual threads или WebFlux).
     * Задержка ограничена 5000 мс для предотвращения зависания.
     *
     * @param ms  желаемая задержка в миллисекундах (максимум 5000)
     * @return JSON с ID инстанса и фактической задержкой
     */
    @GetMapping("/delay/{ms}")
    public ResponseEntity<Map<String, Object>> delay(@PathVariable int ms) throws InterruptedException {
        int actualDelay = Math.min(ms, 5000);
        Thread.sleep(actualDelay);

        log.debug("Delay request served: instanceId={} delayMs={}", instanceId, actualDelay);

        return ResponseEntity.ok(Map.of(
            "instanceId", instanceId,
            "delayMs",    actualDelay
        ));
    }

    /**
     * CPU-нагрузка: вычисляет сумму квадратных корней.
     *
     * <p>Используется для тестирования реакции {@code OverloadHealthIndicator}
     * и {@code AdaptiveLoadBalancer} на реальную нагрузку.
     * Число итераций ограничено 10 млн для предотвращения зависания.
     *
     * @param iterations число итераций sqrt (максимум 10 000 000)
     * @return JSON с ID инстанса и результатом вычисления
     */
    @GetMapping("/cpu/{iterations}")
    public ResponseEntity<Map<String, Object>> cpuLoad(@PathVariable int iterations) {
        int bounded = Math.min(iterations, 10_000_000);
        double result = 0.0;

        for (int i = 1; i <= bounded; i++) {
            result += Math.sqrt(i);
        }

        log.debug("CPU request served: instanceId={} iterations={} result={}",
            instanceId, bounded, result);

        return ResponseEntity.ok(Map.of(
            "instanceId", instanceId,
            "iterations", bounded,
            "result",     result
        ));
    }
}
