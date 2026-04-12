package com.dmitrymrsh.bunsan.algorithm;

import com.dmitrymrsh.bunsan.starter.BunsanProperties.LeastConnectionsProperties;
import com.dmitrymrsh.bunsan.starter.BunsanProperties.LeastConnectionsProperties.TieBreaking;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultResponse;
import org.springframework.cloud.client.loadbalancer.EmptyResponse;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Балансировщик нагрузки по принципу наименьшего числа активных соединений.
 *
 * <h2>Алгоритм</h2>
 * <ol>
 *   <li>Для каждого доступного инстанса читается число in-flight запросов
 *       из разделяемого {@link ConnectionTracker}.</li>
 *   <li>Находится минимальное значение среди всех инстансов.</li>
 *   <li>Если несколько инстансов имеют одинаковый минимум — выбор производится
 *       согласно настройке {@link LeastConnectionsProperties#getTieBreaking()}:
 *       случайно ({@code RANDOM}) или первый в списке ({@code FIRST}).</li>
 *   <li>Счётчик выбранного инстанса инкрементируется через {@link ConnectionTracker#increment(String)}.</li>
 *   <li>По завершении запроса (успех/ошибка/отмена) {@code LatencyTrackingGlobalFilter}
 *       вызывает {@link ConnectionTracker#decrement(String)} в блоке {@code doFinally}.</li>
 * </ol>
 *
 * <h2>Ограничение — client-side учёт</h2>
 * Каждый gateway-инстанс ведёт собственные счётчики и не знает о соединениях,
 * открытых другими gateway-инстансами. Это ограничение зафиксировано как
 * известная особенность архитектуры клиентской балансировки.
 *
 * <p><b>Блокирующие вызовы запрещены</b> — {@link #choose(Request)} реактивен.
 */
public class LeastConnectionsLoadBalancer implements ReactorServiceInstanceLoadBalancer {

    private static final Logger log = LoggerFactory.getLogger(LeastConnectionsLoadBalancer.class);

    private final ObjectProvider<ServiceInstanceListSupplier> serviceInstanceListSupplierProvider;
    private final String serviceId;
    private final ConnectionTracker connectionTracker;
    private final LeastConnectionsProperties properties;
    private final MeterRegistry meterRegistry;

    /**
     * Создаёт балансировщик с алгоритмом наименьших соединений.
     *
     * @param serviceInstanceListSupplierProvider поставщик инстансов
     * @param serviceId                           имя сервиса
     * @param connectionTracker                   разделяемый трекер in-flight запросов
     * @param meterRegistry                       реестр метрик Micrometer
     * @param properties                          настройки алгоритма
     */
    public LeastConnectionsLoadBalancer(
        ObjectProvider<ServiceInstanceListSupplier> serviceInstanceListSupplierProvider,
        String serviceId,
        ConnectionTracker connectionTracker,
        MeterRegistry meterRegistry,
        LeastConnectionsProperties properties
    ) {
        this.serviceInstanceListSupplierProvider = serviceInstanceListSupplierProvider;
        this.serviceId = serviceId;
        this.connectionTracker = connectionTracker;
        this.meterRegistry = meterRegistry;
        this.properties = properties;
    }

    /**
     * Выбирает инстанс с наименьшим числом активных запросов.
     *
     * @param request входящий запрос
     * @return {@link Mono} с выбранным инстансом
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
                return pickLeastConnections(instances);
            });
    }

    /**
     * Выбирает инстанс с наименьшим числом активных соединений
     * и инкрементирует его счётчик.
     */
    private Response<ServiceInstance> pickLeastConnections(List<ServiceInstance> instances) {
        // Находим минимальное число соединений
        int minConnections = instances.stream()
            .mapToInt(i -> connectionTracker.getInFlight(i.getInstanceId()))
            .min()
            .orElse(0);

        // Фильтруем инстансы с минимумом
        List<ServiceInstance> candidates = instances.stream()
            .filter(i -> connectionTracker.getInFlight(i.getInstanceId()) == minConnections)
            .toList();

        // Разрешаем ничью согласно настройке tie-breaking
        ServiceInstance chosen = resolveTie(candidates);

        // Инкрементируем счётчик — декремент произведёт LatencyTrackingGlobalFilter
        int newCount = connectionTracker.increment(chosen.getInstanceId());

        log.debug("Chosen instance: serviceId={} instanceId={} inFlight={} candidates={}",
            serviceId, chosen.getInstanceId(), newCount, candidates.size());

        // Публикуем gauge метрику (актуальное значение in-flight)
        meterRegistry.gauge(
            "bunsan_inflight_requests",
            List.of(io.micrometer.core.instrument.Tag.of("instance_id", chosen.getInstanceId())),
            connectionTracker,
            ct -> ct.getInFlight(chosen.getInstanceId())
        );

        return new DefaultResponse(chosen);
    }

    /**
     * Разрешает ничью при одинаковом числе соединений у нескольких инстансов.
     *
     * @param candidates инстансы с одинаковым минимальным числом соединений
     * @return выбранный инстанс
     */
    private ServiceInstance resolveTie(List<ServiceInstance> candidates) {
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        return switch (properties.getTieBreaking()) {
            case RANDOM -> candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            case FIRST  -> candidates.getFirst();
        };
    }

    // Заглушка-поставщик для случая недоступности реального
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
