package com.dmitrymrsh.bunsan.metrics;

import com.dmitrymrsh.bunsan.algorithm.ConnectionTracker;
import com.dmitrymrsh.bunsan.algorithm.LatencyTracker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Компонент публикации кастомных метрик балансировщика в Micrometer / Prometheus.
 *
 * <h2>Публикуемые метрики</h2>
 * <table>
 *   <tr><th>Метрика</th><th>Тип</th><th>Описание</th></tr>
 *   <tr>
 *     <td>{@code bunsan_requests_total}</td>
 *     <td>Counter</td>
 *     <td>Число запросов по инстансам и стратегии балансировки</td>
 *   </tr>
 *   <tr>
 *     <td>{@code bunsan_response_time_seconds}</td>
 *     <td>Timer (histogram)</td>
 *     <td>Latency на стороне клиента (gateway) по инстансам</td>
 *   </tr>
 * </table>
 *
 * <p>Дополнительные gauge-метрики ({@code bunsan_inflight_requests},
 * {@code bunsan_instance_weight}, {@code bunsan_instance_score},
 * {@code bunsan_instance_filtered_total}) публикуются непосредственно
 * в балансировщиках и {@link com.dmitrymrsh.bunsan.discovery.OverloadInstanceFilter}.
 *
 * <p>Метод {@link #recordRequest(String, String, long)} вызывается из
 * {@link LatencyTrackingGlobalFilter} после каждого завершённого запроса.
 */
public class LoadBalancerMetrics {

    /** Имя метрики счётчика запросов. */
    public static final String METRIC_REQUESTS_TOTAL = "bunsan_requests_total";

    /** Имя метрики таймера (histogram) latency. */
    public static final String METRIC_RESPONSE_TIME = "bunsan_response_time_seconds";

    private final MeterRegistry meterRegistry;

    // Кэш Counter'ов по ключу "instanceId:strategy" для исключения повторной регистрации
    private final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();

    // Кэш Timer'ов по instanceId
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();

    /**
     * Создаёт компонент метрик.
     *
     * @param meterRegistry     реестр метрик Micrometer
     * @param latencyTracker    трекер latency (используется для gauge при необходимости)
     * @param connectionTracker трекер соединений (используется для gauge при необходимости)
     */
    public LoadBalancerMetrics(
        MeterRegistry meterRegistry,
        LatencyTracker latencyTracker,
        ConnectionTracker connectionTracker
    ) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Фиксирует завершённый запрос: инкрементирует счётчик и записывает latency в histogram.
     *
     * <p>Метрики кэшируются по ключу {@code instanceId + strategy}, чтобы избежать
     * повторной регистрации одного и того же метра в {@link MeterRegistry}.
     *
     * @param instanceId  идентификатор инстанса, обслужившего запрос
     * @param strategy    название алгоритма балансировки (например, {@code weighted-response-time})
     * @param latencyMs   время ответа в миллисекундах
     */
    public void recordRequest(String instanceId, String strategy, long latencyMs) {
        // Счётчик запросов
        String counterKey = instanceId + ":" + strategy;
        counterCache.computeIfAbsent(counterKey, k ->
            Counter.builder(METRIC_REQUESTS_TOTAL)
                .description("Total requests dispatched by load balancer")
                .tag("instance_id", instanceId)
                .tag("strategy", strategy)
                .register(meterRegistry)
        ).increment();

        // Histogram latency
        timerCache.computeIfAbsent(instanceId, k ->
            Timer.builder(METRIC_RESPONSE_TIME)
                .description("Client-side response time measured by gateway")
                .tag("instance_id", instanceId)
                .publishPercentileHistogram(true)
                .register(meterRegistry)
        ).record(latencyMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Регистрирует gauge-метрику весов инстансов для алгоритма WeightedResponseTime.
     *
     * @param instanceId идентификатор инстанса
     * @param weight     текущий вес (обратная latency)
     */
    public void recordInstanceWeight(String instanceId, double weight) {
        meterRegistry.gauge(
            "bunsan_instance_weight",
            List.of(Tag.of("instance_id", instanceId)),
            weight
        );
    }
}
