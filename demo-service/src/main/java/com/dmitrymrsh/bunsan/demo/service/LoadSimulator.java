package com.dmitrymrsh.bunsan.demo.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Симулятор CPU-нагрузки для демонстрации реакции балансировщиков на деградацию.
 *
 * <p>Активируется при наличии переменной окружения {@code STRESS_ENABLED=true}.
 * В docker-compose один из инстансов demo-service запускается с этим флагом.
 *
 * <h2>Режим работы</h2>
 * После старта запускает пул virtual thread'ов, каждый из которых выполняет
 * интенсивные вычисления (sqrt в цикле) с паузами между итерациями.
 * Это имитирует реалистичную нагрузку: CPU постепенно растёт и стабилизируется
 * на уровне, превышающем {@code bunsan.discovery.overload-filter.cpu-threshold}.
 *
 * <p>При превышении порога {@code OverloadHealthIndicator} переводит инстанс
 * в статус {@code OUT_OF_SERVICE}, и он исключается из ответов Eureka-реестра.
 *
 * <p>Число CPU-workers настраивается через {@code STRESS_WORKERS} (по умолчанию 2).
 */
@Component
public class LoadSimulator {

    private static final Logger log = LoggerFactory.getLogger(LoadSimulator.class);

    @Value("${STRESS_ENABLED:false}")
    private boolean stressEnabled;

    @Value("${STRESS_WORKERS:2}")
    private int stressWorkers;

    private ScheduledExecutorService scheduler;

    /**
     * Запускает симулятор нагрузки при старте приложения, если {@code STRESS_ENABLED=true}.
     *
     * <p>Использует virtual threads (Java 21+) для создания лёгких потоков-workers.
     */
    @PostConstruct
    public void start() {
        if (!stressEnabled) {
            log.info("LoadSimulator: stress mode is DISABLED");
            return;
        }

        log.warn("LoadSimulator: stress mode ENABLED with {} workers — CPU load will increase", stressWorkers);

        // Планировщик на virtual thread'ах — минимальные накладные расходы
        scheduler = Executors.newScheduledThreadPool(
            stressWorkers,
            Thread.ofVirtual().name("stress-worker-", 0).factory()
        );

        for (int i = 0; i < stressWorkers; i++) {
            final int workerId = i;
            scheduler.scheduleAtFixedRate(
                () -> burnCpu(workerId),
                0,
                15,
                TimeUnit.MILLISECONDS
            );
        }
    }

    /**
     * Вычислительная задача для нагрузки CPU.
     *
     * <p>Итерирует 500 000 раз, вычисляя sqrt и логарифм.
     * Результат записывается в volatile для предотвращения оптимизации JIT.
     *
     * @param workerId номер worker'а (для логирования)
     */
    @SuppressWarnings("unused")
    private void burnCpu(int workerId) {
        double accumulator = 0.0;
        for (int i = 1; i <= 500_000; i++) {
            accumulator += Math.sqrt(i) + Math.log(i);
        }
        // Используем результат, чтобы JIT не оптимизировал цикл
        if (accumulator < 0) {
            log.trace("Worker {} unexpected accumulator: {}", workerId, accumulator);
        }
    }

    /**
     * Корректно останавливает симулятор при завершении приложения.
     */
    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            log.info("LoadSimulator: shutting down stress workers");
            scheduler.shutdownNow();
        }
    }
}
