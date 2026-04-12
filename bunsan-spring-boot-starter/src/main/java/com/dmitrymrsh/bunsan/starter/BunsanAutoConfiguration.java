package com.dmitrymrsh.bunsan.starter;

import com.dmitrymrsh.bunsan.algorithm.ConnectionTracker;
import com.dmitrymrsh.bunsan.algorithm.LatencyTracker;
import com.dmitrymrsh.bunsan.discovery.OverloadHealthIndicator;
import com.dmitrymrsh.bunsan.discovery.OverloadInstanceFilter;
import com.dmitrymrsh.bunsan.metrics.LbHealthEndpoint;
import com.dmitrymrsh.bunsan.metrics.LoadBalancerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.cloud.loadbalancer.config.LoadBalancerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Точка входа автоконфигурации {@code bunsan-spring-boot-starter}.
 *
 * <p>Регистрируется через
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * и подключается автоматически при наличии стартера в classpath.
 *
 * <h2>Что создаётся</h2>
 * <ul>
 *   <li>{@link LatencyTracker} — разделяемый EMA-трекер latency (всегда).</li>
 *   <li>{@link ConnectionTracker} — разделяемый трекер in-flight запросов (всегда).</li>
 *   <li>{@link LoadBalancerMetrics} — публикатор кастомных метрик Micrometer (всегда).</li>
 *   <li>{@link LbHealthEndpoint} — Actuator-эндпоинт {@code /actuator/lb-health} (всегда).</li>
 *   <li>{@link OverloadHealthIndicator} — health indicator для Eureka-сервисов
 *       (при наличии {@code eureka-client} в classpath).</li>
 *   <li>{@link OverloadInstanceFilter} — AOP-фильтр реестра на Eureka Server
 *       (при наличии {@code eureka-server} в classpath и флаге {@code enabled=true}).</li>
 *   <li>{@link LatencyTrackingGlobalFilter} — GlobalFilter для gateway
 *       (при наличии {@code spring-cloud-gateway-server} в classpath).</li>
 * </ul>
 *
 * <p>Алгоритм балансировки конфигурируется через {@link BunsanClientConfiguration},
 * которая регистрируется в дочернем контексте каждого LB-клиента через
 * {@link LoadBalancerClients @LoadBalancerClients}.
 */
@AutoConfiguration(after = LoadBalancerAutoConfiguration.class)
@EnableConfigurationProperties(BunsanProperties.class)
@LoadBalancerClients(defaultConfiguration = BunsanClientConfiguration.class)
public class BunsanAutoConfiguration {

    // -------------------------------------------------------------------------
    // Разделяемые трекеры (родительский контекст)
    // -------------------------------------------------------------------------

    /**
     * EMA-трекер latency. Заполняется {@link LatencyTrackingGlobalFilter},
     * читается балансировщиками {@code WeightedResponseTime} и {@code Adaptive}.
     *
     * @param properties настройки стартера (для ema-alpha и window-size)
     * @return синглтон {@link LatencyTracker}
     */
    @Bean
    @ConditionalOnMissingBean
    public LatencyTracker latencyTracker(BunsanProperties properties) {
        return new LatencyTracker(
            properties.getWeightedResponseTime().getEmaAlpha(),
            properties.getWeightedResponseTime().getWindowSize()
        );
    }

    /**
     * Трекер in-flight запросов для алгоритма {@code LeastConnections}.
     * Инкрементируется балансировщиком, декрементируется GlobalFilter.
     *
     * @return синглтон {@link ConnectionTracker}
     */
    @Bean
    @ConditionalOnMissingBean
    public ConnectionTracker connectionTracker() {
        return new ConnectionTracker();
    }

    // -------------------------------------------------------------------------
    // Метрики
    // -------------------------------------------------------------------------

    /**
     * Публикатор кастомных метрик балансировщика в Micrometer.
     *
     * @param meterRegistry реестр метрик
     * @param latencyTracker трекер latency
     * @param connectionTracker трекер соединений
     * @return бин {@link LoadBalancerMetrics}
     */
    @Bean
    @ConditionalOnMissingBean
    public LoadBalancerMetrics loadBalancerMetrics(
        MeterRegistry meterRegistry,
        LatencyTracker latencyTracker,
        ConnectionTracker connectionTracker
    ) {
        return new LoadBalancerMetrics(meterRegistry, latencyTracker, connectionTracker);
    }

    /**
     * Actuator-эндпоинт {@code /actuator/lb-health}.
     * Возвращает CPU load, heap usage и active threads ratio текущего инстанса.
     * Используется {@code AdaptiveLoadBalancer} для сбора метрик.
     *
     * @return бин {@link LbHealthEndpoint}
     */
    @Bean
    @ConditionalOnMissingBean
    public LbHealthEndpoint lbHealthEndpoint() {
        return new LbHealthEndpoint();
    }

    // -------------------------------------------------------------------------
    // Eureka Client: OverloadHealthIndicator
    // -------------------------------------------------------------------------

    /**
     * Health indicator для сервисных инстансов.
     *
     * <p>При превышении порогов CPU или heap переводит инстанс в статус
     * {@code OUT_OF_SERVICE}. Eureka-клиент транслирует этот статус в heartbeat,
     * что делает инстанс видимым как перегруженный для {@link OverloadInstanceFilter}.
     *
     * <p>Создаётся только если Eureka Client ({@code EurekaClient}) присутствует в classpath.
     *
     * @param properties настройки фильтра перегрузки
     * @return бин {@link OverloadHealthIndicator}
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "com.netflix.discovery.EurekaClient")
    public OverloadHealthIndicator overloadHealthIndicator(BunsanProperties properties) {
        return new OverloadHealthIndicator(properties.getDiscovery().getOverloadFilter());
    }

    // -------------------------------------------------------------------------
    // Eureka Server: OverloadInstanceFilter
    // -------------------------------------------------------------------------

    /**
     * AOP-фильтр реестра Eureka Server.
     *
     * <p>Перехватывает вызовы {@code PeerAwareInstanceRegistry.getApplications()} и
     * исключает из ответа инстансы со статусом {@code OUT_OF_SERVICE}, когда
     * {@code bunsan.discovery.overload-filter.enabled=true}.
     *
     * <p>Создаётся только если Eureka Server ({@code PeerAwareInstanceRegistry})
     * присутствует в classpath и фильтр включён.
     *
     * @param properties    настройки фильтра
     * @param meterRegistry реестр метрик
     * @return бин {@link OverloadInstanceFilter}
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "com.netflix.eureka.registry.PeerAwareInstanceRegistry")
    @ConditionalOnProperty(
        name = "bunsan.discovery.overload-filter.enabled",
        havingValue = "true",
        matchIfMissing = true
    )
    public OverloadInstanceFilter overloadInstanceFilter(
        BunsanProperties properties,
        MeterRegistry meterRegistry
    ) {
        return new OverloadInstanceFilter(properties.getDiscovery().getOverloadFilter(), meterRegistry);
    }

    // -------------------------------------------------------------------------
    // Gateway: LatencyTrackingGlobalFilter
    // Вынесено в отдельный @Configuration класс с @ConditionalOnClass НА УРОВНЕ КЛАССА.
    // Это предотвращает загрузку класса LatencyTrackingGlobalFilter (и транзитивно
    // GlobalFilter) при интроспекции BunsanAutoConfiguration в окружениях без Gateway.
    // -------------------------------------------------------------------------

    /**
     * Конфигурация Gateway-фильтра, изолированная от основного класса.
     *
     * <p>Spring проверяет наличие {@code GlobalFilter} в classpath на уровне класса
     * и пропускает всю конфигурацию целиком, если класс отсутствует — без загрузки
     * {@link com.dmitrymrsh.bunsan.metrics.LatencyTrackingGlobalFilter}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.cloud.gateway.filter.GlobalFilter")
    static class GatewayFilterConfiguration {

        /**
         * GlobalFilter для Spring Cloud Gateway.
         *
         * <p>Перехватывает каждый проксированный запрос: фиксирует время начала,
         * записывает latency в {@link LatencyTracker} и декрементирует счётчик
         * in-flight в {@link ConnectionTracker} в блоке {@code doFinally}.
         *
         * @param latencyTracker    разделяемый EMA-трекер latency
         * @param connectionTracker разделяемый трекер in-flight запросов
         * @param meterRegistry     реестр метрик
         * @return бин {@link com.dmitrymrsh.bunsan.metrics.LatencyTrackingGlobalFilter}
         */
        @Bean
        @ConditionalOnMissingBean
        public com.dmitrymrsh.bunsan.metrics.LatencyTrackingGlobalFilter latencyTrackingGlobalFilter(
            LatencyTracker latencyTracker,
            ConnectionTracker connectionTracker,
            MeterRegistry meterRegistry
        ) {
            return new com.dmitrymrsh.bunsan.metrics.LatencyTrackingGlobalFilter(
                latencyTracker, connectionTracker, meterRegistry);
        }
    }
}
