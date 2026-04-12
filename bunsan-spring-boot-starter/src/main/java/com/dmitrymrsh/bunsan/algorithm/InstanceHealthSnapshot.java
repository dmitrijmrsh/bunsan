package com.dmitrymrsh.bunsan.algorithm;

import java.time.Instant;

/**
 * Снимок метрик здоровья инстанса, полученный с {@code /actuator/lb-health}.
 *
 * <p>Используется {@link AdaptiveLoadBalancer} для вычисления составного score.
 * Снимок считается устаревшим, если разница между текущим временем и
 * {@link #capturedAt()} превышает три периода опроса
 * ({@link com.dmitrymrsh.bunsan.starter.BunsanProperties.AdaptiveProperties#metricsPollInterval}).
 *
 * <p>Реализован как Java record — неизменяемый носитель данных (Java 16+).
 *
 * @param cpuLoad             загрузка CPU процессом JVM (0.0–1.0)
 * @param heapUsagePercent    использование heap-памяти (0.0–1.0)
 * @param activeThreadsRatio  отношение активных потоков к максимуму (0.0–1.0)
 * @param capturedAt          момент снятия замера (UTC)
 */
public record InstanceHealthSnapshot(
    double cpuLoad,
    double heapUsagePercent,
    double activeThreadsRatio,
    Instant capturedAt
) {
    /**
     * Создаёт снимок с текущим временем захвата.
     *
     * @param cpuLoad            загрузка CPU (0.0–1.0)
     * @param heapUsagePercent   использование heap (0.0–1.0)
     * @param activeThreadsRatio отношение активных потоков (0.0–1.0)
     * @return новый снимок с {@code capturedAt = Instant.now()}
     */
    public static InstanceHealthSnapshot now(
        double cpuLoad,
        double heapUsagePercent,
        double activeThreadsRatio
    ) {
        return new InstanceHealthSnapshot(cpuLoad, heapUsagePercent, activeThreadsRatio, Instant.now());
    }

    /**
     * Проверяет, устарел ли снимок относительно заданного числа секунд.
     *
     * @param stalenessThresholdSeconds порог устаревания в секундах
     * @return {@code true} если снимок старше порога
     */
    public boolean isStale(long stalenessThresholdSeconds) {
        return capturedAt.plusSeconds(stalenessThresholdSeconds).isBefore(Instant.now());
    }
}
