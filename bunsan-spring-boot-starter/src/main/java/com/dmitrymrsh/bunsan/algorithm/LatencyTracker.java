package com.dmitrymrsh.bunsan.algorithm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Разделяемый компонент для отслеживания latency инстансов через
 * экспоненциальное скользящее среднее (EMA).
 *
 * <p>EMA-формула обновления: {@code newAvg = α * currentLatency + (1 - α) * oldAvg},
 * где {@code α} (emaAlpha) определяет чувствительность к последним замерам.
 *
 * <p>Бин зарегистрирован в контексте родительского приложения (через
 * {@link com.dmitrymrsh.bunsan.starter.BunsanAutoConfiguration}) и доступен
 * как из дочернего контекста балансировщика, так и из {@code LatencyTrackingGlobalFilter}.
 *
 * <p>Все операции потокобезопасны — используются {@link ConcurrentHashMap} и {@link AtomicInteger}.
 */
public class LatencyTracker {

    private static final Logger log = LoggerFactory.getLogger(LatencyTracker.class);

    /**
     * Значение latency по умолчанию (мс), возвращаемое при отсутствии замеров.
     * Гарантирует, что новые инстансы получают разумный вес при старте.
     */
    private static final double DEFAULT_LATENCY_MS = 100.0;

    /** EMA-коэффициент из {@link com.dmitrymrsh.bunsan.starter.BunsanProperties.WeightedResponseTimeProperties#emaAlpha}. */
    private final double emaAlpha;

    /** Минимум запросов для активации взвешенного выбора. */
    private final int windowSize;

    /** EMA средняя latency: instanceId → avgLatencyMs. */
    private final ConcurrentHashMap<String, Double> avgLatency = new ConcurrentHashMap<>();

    /** Счётчик запросов: instanceId → count. Используется для определения, набран ли window. */
    private final ConcurrentHashMap<String, AtomicInteger> requestCount = new ConcurrentHashMap<>();

    /**
     * Создаёт трекер с заданными параметрами EMA.
     *
     * @param emaAlpha   коэффициент сглаживания (0.0 < emaAlpha < 1.0)
     * @param windowSize минимум замеров для активации весового выбора
     */
    public LatencyTracker(double emaAlpha, int windowSize) {
        this.emaAlpha = emaAlpha;
        this.windowSize = windowSize;
    }

    /**
     * Фиксирует замер latency для инстанса и обновляет EMA.
     *
     * <p>Если для инстанса ещё нет замеров, текущий замер становится начальным значением.
     * В противном случае применяется EMA-формула.
     *
     * @param instanceId  идентификатор инстанса
     * @param latencyMs   время ответа в миллисекундах
     */
    public void record(String instanceId, long latencyMs) {
        requestCount
            .computeIfAbsent(instanceId, _ -> new AtomicInteger(0))
            .incrementAndGet();

        avgLatency.compute(instanceId, (_, current) ->
            current == null
                ? (double) latencyMs
                : emaAlpha * latencyMs + (1.0 - emaAlpha) * current
        );

        log.trace("Latency recorded: instanceId={} latencyMs={} newAvg={}",
            instanceId, latencyMs, avgLatency.get(instanceId));
    }

    /**
     * Возвращает текущее EMA-среднее latency для инстанса.
     *
     * @param instanceId идентификатор инстанса
     * @return среднее время ответа в миллисекундах; {@code 100.0} при отсутствии замеров
     */
    public double getAvgLatency(String instanceId) {
        return avgLatency.getOrDefault(instanceId, DEFAULT_LATENCY_MS);
    }

    /**
     * Проверяет, достаточно ли замеров для активации взвешенного выбора.
     *
     * <p>Используется {@link WeightedResponseTimeLoadBalancer} для переключения
     * с round-robin fallback на весовой выбор.
     *
     * @param instanceId идентификатор инстанса
     * @return {@code true}, если число запросов ≥ {@link #windowSize}
     */
    public boolean hasEnoughData(String instanceId) {
        AtomicInteger count = requestCount.get(instanceId);
        return count != null && count.get() >= windowSize;
    }

    /**
     * Возвращает настроенный размер окна (минимум замеров).
     *
     * @return windowSize
     */
    public int getWindowSize() {
        return windowSize;
    }
}
