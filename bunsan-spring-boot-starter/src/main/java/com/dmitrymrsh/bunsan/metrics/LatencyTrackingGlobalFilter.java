package com.dmitrymrsh.bunsan.metrics;

import com.dmitrymrsh.bunsan.algorithm.ConnectionTracker;
import com.dmitrymrsh.bunsan.algorithm.LatencyTracker;
import com.dmitrymrsh.bunsan.starter.BunsanProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ReactiveLoadBalancerClientFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Spring Cloud Gateway GlobalFilter для отслеживания latency и in-flight запросов.
 *
 * <h2>Жизненный цикл каждого запроса</h2>
 * <ol>
 *   <li><b>Pre-processing</b> (до отправки в upstream):
 *     <ul>
 *       <li>Читает выбранный балансировщиком инстанс из атрибутов exchange
 *           ({@link ServerWebExchangeUtils#GATEWAY_LOADBALANCER_RESPONSE_ATTR}).</li>
 *       <li>Фиксирует время начала ({@code System.currentTimeMillis()}).</li>
 *     </ul>
 *   </li>
 *   <li><b>Post-processing</b> в блоке {@code doFinally()} (выполняется при любом исходе:
 *       успех, ошибка, отмена):
 *     <ul>
 *       <li>Вычисляет elapsed time и записывает в {@link LatencyTracker}.</li>
 *       <li>Декрементирует счётчик in-flight в {@link ConnectionTracker}.</li>
 *       <li>Публикует метрики через {@link LoadBalancerMetrics}.</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h2>Порядок выполнения</h2>
 * Фильтр запускается с ордером {@link ReactiveLoadBalancerClientFilter#LOAD_BALANCER_CLIENT_FILTER_ORDER}
 * {@code + 50} — т.е. <em>после</em> выбора инстанса балансировщиком,
 * но <em>до</em> фактической отправки запроса в Netty.
 */
public class LatencyTrackingGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(LatencyTrackingGlobalFilter.class);

    /**
     * Ордер фильтра: запускается сразу после {@code ReactiveLoadBalancerClientFilter} (10100).
     * Netty routing filter работает при {@code Integer.MAX_VALUE}, поэтому
     * наш фильтр корректно обрамляет весь upstream-запрос.
     */
    public static final int ORDER = ReactiveLoadBalancerClientFilter.LOAD_BALANCER_CLIENT_FILTER_ORDER + 50;

    private final LatencyTracker latencyTracker;
    private final ConnectionTracker connectionTracker;
    private final LoadBalancerMetrics lbMetrics;
    private final String strategyName;

    /**
     * Создаёт фильтр с зависимостями из родительского контекста.
     *
     * @param latencyTracker    разделяемый EMA-трекер latency
     * @param connectionTracker разделяемый трекер in-flight запросов
     * @param lbMetrics         публикатор кастомных метрик балансировщика
     * @param properties        настройки стартера (для определения текущей стратегии)
     */
    public LatencyTrackingGlobalFilter(
        LatencyTracker latencyTracker,
        ConnectionTracker connectionTracker,
        LoadBalancerMetrics lbMetrics,
        BunsanProperties properties
    ) {
        this.latencyTracker = latencyTracker;
        this.connectionTracker = connectionTracker;
        this.lbMetrics = lbMetrics;
        this.strategyName = properties.getAlgorithm().name().toLowerCase().replace('_', '-');
    }

    /**
     * Фильтрует запрос: замеряет latency и обновляет трекеры по завершении.
     *
     * @param exchange текущий HTTP-обмен
     * @param chain    цепочка фильтров
     * @return {@link Mono<Void>} с прикреплённым doFinally-обработчиком
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Получаем выбранный балансировщиком инстанс
        @SuppressWarnings("unchecked")
        Response<ServiceInstance> lbResponse = (Response<ServiceInstance>)
            exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_LOADBALANCER_RESPONSE_ATTR);

        if (lbResponse == null || !lbResponse.hasServer()) {
            // Балансировщик не выбрал инстанс (например, discovery отключён)
            return chain.filter(exchange);
        }

        String instanceId = lbResponse.getServer().getInstanceId();
        long startTime = System.currentTimeMillis();
        AtomicLong elapsedRef = new AtomicLong(0);

        return chain.filter(exchange)
            .doOnSuccess(v -> elapsedRef.set(System.currentTimeMillis() - startTime))
            .doOnError(e -> elapsedRef.set(System.currentTimeMillis() - startTime))
            .doFinally(signal -> {
                long elapsed = elapsedRef.get() > 0
                    ? elapsedRef.get()
                    : System.currentTimeMillis() - startTime;

                // 1. Обновляем EMA-latency
                latencyTracker.record(instanceId, elapsed);

                // 2. Декрементируем in-flight (для LeastConnections)
                connectionTracker.decrement(instanceId);

                // 3. Публикуем метрики (с histogram-бакетами для histogram_quantile)
                lbMetrics.recordRequest(instanceId, resolveStrategy(exchange), elapsed);

                log.debug("Request completed: instanceId={} elapsed={}ms signal={}",
                    instanceId, elapsed, signal);
            });
    }

    /**
     * Возвращает название текущей стратегии балансировки.
     * Определяется один раз при создании фильтра из {@link BunsanProperties}.
     */
    private String resolveStrategy(ServerWebExchange exchange) {
        return strategyName;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
