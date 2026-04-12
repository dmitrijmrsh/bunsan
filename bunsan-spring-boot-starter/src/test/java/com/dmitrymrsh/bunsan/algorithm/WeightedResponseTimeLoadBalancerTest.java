package com.dmitrymrsh.bunsan.algorithm;

import com.dmitrymrsh.bunsan.starter.BunsanProperties.WeightedResponseTimeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты для {@link WeightedResponseTimeLoadBalancer}.
 *
 * <p>Проверяет:
 * <ul>
 *   <li>fallback на round-robin при недостаточном числе замеров;</li>
 *   <li>взвешенный выбор при наличии данных latency;</li>
 *   <li>пустой ответ при отсутствии инстансов;</li>
 *   <li>статистическое распределение запросов согласно весам.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class WeightedResponseTimeLoadBalancerTest {

    @Mock
    private ObjectProvider<ServiceInstanceListSupplier> supplierProvider;

    @Mock
    private ServiceInstanceListSupplier supplier;

    private LatencyTracker latencyTracker;
    private WeightedResponseTimeProperties properties;
    private WeightedResponseTimeLoadBalancer loadBalancer;

    private static final String SERVICE_ID = "demo-service";
    private static final String INSTANCE_1 = "instance-1";
    private static final String INSTANCE_2 = "instance-2";

    @BeforeEach
    void setUp() {
        properties = new WeightedResponseTimeProperties();
        properties.setEmaAlpha(0.3);
        properties.setWindowSize(3);

        latencyTracker = new LatencyTracker(properties.getEmaAlpha(), properties.getWindowSize());

        when(supplierProvider.getIfAvailable(any())).thenReturn(supplier);

        loadBalancer = new WeightedResponseTimeLoadBalancer(
            supplierProvider, SERVICE_ID, latencyTracker, properties
        );
    }

    @Test
    @DisplayName("Пустой список инстансов → EmptyResponse")
    void emptyInstanceList_returnsEmptyResponse() {
        when(supplier.get(any())).thenReturn(Flux.just(List.of()));

        StepVerifier.create(loadBalancer.choose(mockRequest()))
            .assertNext(response -> assertThat(response.hasServer()).isFalse())
            .verifyComplete();
    }

    @Test
    @DisplayName("Один инстанс → всегда выбирается он")
    void singleInstance_alwaysChosen() {
        ServiceInstance instance = createInstance(INSTANCE_1, "host1", 8080);
        when(supplier.get(any())).thenReturn(Flux.just(List.of(instance)));

        StepVerifier.create(loadBalancer.choose(mockRequest()))
            .assertNext(response -> {
                assertThat(response.hasServer()).isTrue();
                assertThat(response.getServer().getInstanceId()).isEqualTo(INSTANCE_1);
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("Без данных latency → fallback на round-robin (оба инстанса получают трафик)")
    void noLatencyData_usesRoundRobinFallback() {
        ServiceInstance i1 = createInstance(INSTANCE_1, "host1", 8080);
        ServiceInstance i2 = createInstance(INSTANCE_2, "host2", 8081);
        when(supplier.get(any())).thenReturn(Flux.just(List.of(i1, i2)));

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            String chosen = loadBalancer.choose(mockRequest())
                .map(r -> r.getServer().getInstanceId())
                .block();
            counts.merge(chosen, 1, Integer::sum);
        }

        // Round-robin: оба инстанса должны получить примерно одинаковое число запросов
        assertThat(counts).containsKey(INSTANCE_1);
        assertThat(counts).containsKey(INSTANCE_2);
    }

    @Test
    @DisplayName("Быстрый инстанс получает больше трафика при взвешенном выборе")
    @RepeatedTest(3)
    void fastInstanceGetsMoreTraffic() {
        ServiceInstance fast = createInstance(INSTANCE_1, "host1", 8080);
        ServiceInstance slow = createInstance(INSTANCE_2, "host2", 8081);

        // INSTANCE_1 быстрый (20 мс), INSTANCE_2 медленный (200 мс)
        // Записываем данные, превышающие windowSize = 3
        for (int i = 0; i < 5; i++) {
            latencyTracker.record(INSTANCE_1, 20);
            latencyTracker.record(INSTANCE_2, 200);
        }

        when(supplier.get(any())).thenReturn(Flux.just(List.of(fast, slow)));

        Map<String, Integer> counts = new HashMap<>();
        int total = 1000;
        for (int i = 0; i < total; i++) {
            String chosen = loadBalancer.choose(mockRequest())
                .map(r -> r.getServer().getInstanceId())
                .block();
            counts.merge(chosen, 1, Integer::sum);
        }

        int fastCount = counts.getOrDefault(INSTANCE_1, 0);
        int slowCount = counts.getOrDefault(INSTANCE_2, 0);

        // Ожидаем ~10:1 (200мс/20мс) распределение.
        // С учётом случайности — проверяем, что fast получил > 70% трафика.
        double fastPercent = (double) fastCount / total;
        assertThat(fastPercent)
            .as("Быстрый инстанс должен получить > 70%% трафика, получил %.1f%%", fastPercent * 100)
            .isGreaterThan(0.70);
    }

    @Test
    @DisplayName("LatencyTracker.hasEnoughData() активируется после windowSize замеров")
    void latencyTrackerActivatesAfterWindowSize() {
        int windowSize = 3;

        assertThat(latencyTracker.hasEnoughData(INSTANCE_1)).isFalse();

        for (int i = 1; i <= windowSize; i++) {
            latencyTracker.record(INSTANCE_1, 50L);
            if (i < windowSize) {
                assertThat(latencyTracker.hasEnoughData(INSTANCE_1))
                    .as("После %d замеров (%d < windowSize=%d) — данных ещё недостаточно", i, i, windowSize)
                    .isFalse();
            }
        }

        assertThat(latencyTracker.hasEnoughData(INSTANCE_1))
            .as("После windowSize=%d замеров — должны быть данные", windowSize)
            .isTrue();
    }

    @Test
    @DisplayName("EMA сглаживает значения latency корректно")
    void emaFormula_correctlySmooths() {
        double alpha = 0.3;
        // Первый замер: 100 мс
        latencyTracker.record(INSTANCE_1, 100);
        assertThat(latencyTracker.getAvgLatency(INSTANCE_1)).isEqualTo(100.0);

        // Второй замер: 200 мс → EMA = 0.3 * 200 + 0.7 * 100 = 60 + 70 = 130
        latencyTracker.record(INSTANCE_1, 200);
        assertThat(latencyTracker.getAvgLatency(INSTANCE_1)).isCloseTo(130.0, org.assertj.core.data.Offset.offset(0.01));
    }

    // -------------------------------------------------------------------------
    // Вспомогательные методы
    // -------------------------------------------------------------------------

    private static ServiceInstance createInstance(String instanceId, String host, int port) {
        return new DefaultServiceInstance(instanceId, "demo-service", host, port, false);
    }

    @SuppressWarnings("unchecked")
    private static Request<Object> mockRequest() {
        return org.mockito.Mockito.mock(Request.class);
    }
}
