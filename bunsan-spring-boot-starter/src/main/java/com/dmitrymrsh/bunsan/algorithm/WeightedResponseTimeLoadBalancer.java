package com.dmitrymrsh.bunsan.algorithm;

import com.dmitrymrsh.bunsan.metrics.LoadBalancerMetrics;
import com.dmitrymrsh.bunsan.starter.BunsanProperties.WeightedResponseTimeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultResponse;
import org.springframework.cloud.client.loadbalancer.EmptyResponse;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.cloud.loadbalancer.core.RoundRobinLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Балансировщик нагрузки с взвешенным средним временем ответа.
 *
 * <h2>Алгоритм</h2>
 * <ol>
 *   <li>Для каждого инстанса вычисляется вес: {@code weight = 1.0 / avgLatencyMs}.
 *       Чем меньше latency — тем больше вес и больше трафика получает инстанс.</li>
 *   <li>Веса нормализуются в вероятности (сумма = 1.0).</li>
 *   <li>Инстанс выбирается случайно по нормализованным вероятностям
 *       через {@link ThreadLocalRandom} (без блокировок).</li>
 * </ol>
 *
 * <h2>Fallback</h2>
 * Пока для инстанса не накоплено {@link WeightedResponseTimeProperties#getWindowSize()}
 * замеров — используется round-robin (все новые инстансы стартуют в равных условиях).
 *
 * <h2>Обновление latency</h2>
 * Замеры поступают из {@link LatencyTracker}, который заполняется
 * {@code LatencyTrackingGlobalFilter} после каждого запроса через gateway.
 * Формула EMA настраивается через {@code bunsan.weighted-response-time.ema-alpha}.
 *
 * <p><b>Блокирующие вызовы запрещены</b> — метод {@link #choose(Request)} работает
 * полностью реактивно и возвращает {@link Mono}.
 */
public class WeightedResponseTimeLoadBalancer implements ReactorServiceInstanceLoadBalancer {

    private static final Logger log = LoggerFactory.getLogger(WeightedResponseTimeLoadBalancer.class);

    private final ObjectProvider<ServiceInstanceListSupplier> serviceInstanceListSupplierProvider;
    private final String serviceId;
    private final LatencyTracker latencyTracker;
    private final LoadBalancerMetrics lbMetrics;
    private final WeightedResponseTimeProperties properties;

    /** Round-robin счётчик для fallback-режима (пока не набран window-size). */
    private final AtomicInteger position = new AtomicInteger(0);

    /**
     * Создаёт балансировщик с взвешенным временем ответа.
     *
     * @param serviceInstanceListSupplierProvider поставщик инстансов сервиса
     * @param serviceId                           имя сервиса (например, {@code demo-service})
     * @param latencyTracker                      разделяемый трекер EMA-latency
     * @param lbMetrics                           публикатор кастомных метрик балансировщика
     * @param properties                          настройки алгоритма
     */
    public WeightedResponseTimeLoadBalancer(
        ObjectProvider<ServiceInstanceListSupplier> serviceInstanceListSupplierProvider,
        String serviceId,
        LatencyTracker latencyTracker,
        LoadBalancerMetrics lbMetrics,
        WeightedResponseTimeProperties properties
    ) {
        this.serviceInstanceListSupplierProvider = serviceInstanceListSupplierProvider;
        this.serviceId = serviceId;
        this.latencyTracker = latencyTracker;
        this.lbMetrics = lbMetrics;
        this.properties = properties;
    }

    /**
     * Выбирает инстанс для следующего запроса.
     *
     * <p>Если хотя бы один инстанс не накопил достаточно замеров —
     * активируется round-robin fallback для честного прогрева.
     * После набора данных переключается на взвешенный случайный выбор.
     *
     * @param request входящий запрос (контекст балансировки)
     * @return {@link Mono} с выбранным инстансом или {@link EmptyResponse} при пустом реестре
     */
    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        ServiceInstanceListSupplier supplier =
            serviceInstanceListSupplierProvider.getIfAvailable(NoopServiceInstanceListSupplier::new);

        return supplier.get(request).next()
            .map(instances -> {
                if (instances.isEmpty()) {
                    log.warn("No instances available for serviceId={}", serviceId);
                    return new EmptyResponse();
                }
                return processInstances(instances);
            });
    }

    /**
     * Выбирает инстанс из списка: взвешенно или через round-robin fallback.
     */
    private Response<ServiceInstance> processInstances(List<ServiceInstance> instances) {
        boolean allHaveData = instances.stream()
            .allMatch(i -> latencyTracker.hasEnoughData(i.getInstanceId()));

        ServiceInstance chosen = allHaveData
            ? weightedRandom(instances)
            : roundRobinFallback(instances);

        log.debug("Chosen instance: serviceId={} instanceId={} host={} allHaveData={}",
            serviceId, chosen.getInstanceId(), chosen.getHost(), allHaveData);

        return new DefaultResponse(chosen);
    }

    /**
     * Взвешенный случайный выбор пропорционально обратному значению latency.
     *
     * <p>Инстанс с latency 50 мс получит вдвое больше трафика, чем инстанс с 100 мс.
     */
    private ServiceInstance weightedRandom(List<ServiceInstance> instances) {
        double[] weights = new double[instances.size()];
        double totalWeight = 0.0;

        for (int i = 0; i < instances.size(); i++) {
            // Защита от деления на ноль: минимальная latency 0.1 мс
            double latency = Math.max(latencyTracker.getAvgLatency(instances.get(i).getInstanceId()), 0.1);
            weights[i] = 1.0 / latency;
            totalWeight += weights[i];
            lbMetrics.recordInstanceWeight(instances.get(i).getInstanceId(), weights[i]);
        }

        double random = ThreadLocalRandom.current().nextDouble(totalWeight);
        double cumulative = 0.0;

        for (int i = 0; i < instances.size(); i++) {
            cumulative += weights[i];
            if (random < cumulative) {
                return instances.get(i);
            }
        }
        // Fallback на последний (численная погрешность)
        return instances.get(instances.size() - 1);
    }

    /**
     * Round-robin fallback: используется до накопления достаточного числа замеров.
     */
    private ServiceInstance roundRobinFallback(List<ServiceInstance> instances) {
        int pos = Math.abs(position.getAndIncrement() % instances.size());
        return instances.get(pos);
    }

    // Заглушка: используется как default при недоступности реального поставщика
    private static final class NoopServiceInstanceListSupplier
        implements ServiceInstanceListSupplier {

        @Override
        public String getServiceId() { return "noop"; }

        @Override
        public reactor.core.publisher.Flux<List<ServiceInstance>> get() {
            return reactor.core.publisher.Flux.just(List.of());
        }
    }
}
