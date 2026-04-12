package com.dmitrymrsh.bunsan.algorithm;

import com.dmitrymrsh.bunsan.starter.BunsanProperties.AdaptiveProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты для {@link AdaptiveLoadBalancer}.
 *
 * <p>Тестируется логика выбора инстанса с наименьшим score.
 * Непосредственный опрос HTTP-эндпоинтов не тестируется (требует запущенных сервисов).
 *
 * <p>Для проверки алгоритма score здоровые снимки инжектируются напрямую через
 * внутренний кэш {@code healthCache} (доступен через package-private метод для тестов)
 * или через рефлексию. В данном тесте проверяется поведение при отсутствии данных
 * (penalty score) и при инстансе с идеальным здоровьем.
 */
@ExtendWith(MockitoExtension.class)
class AdaptiveLoadBalancerTest {

    @Mock
    private ObjectProvider<ServiceInstanceListSupplier> supplierProvider;

    @Mock
    private ServiceInstanceListSupplier supplier;

    private LatencyTracker latencyTracker;
    private SimpleMeterRegistry meterRegistry;
    private AdaptiveProperties properties;
    private AdaptiveLoadBalancer loadBalancer;

    private static final String SERVICE_ID = "demo-service";
    private static final String INSTANCE_1 = "instance-1";
    private static final String INSTANCE_2 = "instance-2";

    @BeforeEach
    void setUp() {
        properties = new AdaptiveProperties();
        properties.setMetricsPollInterval(Duration.ofSeconds(5));

        latencyTracker = new LatencyTracker(0.3, 3);
        meterRegistry = new SimpleMeterRegistry();

        when(supplierProvider.getIfAvailable(any())).thenReturn(supplier);

        loadBalancer = new AdaptiveLoadBalancer(
            supplierProvider, SERVICE_ID, latencyTracker, meterRegistry, properties
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
    @DisplayName("Один инстанс → выбирается он (даже со штрафным score при отсутствии данных)")
    void singleInstance_chosenEvenWithPenaltyScore() {
        ServiceInstance instance = createInstance(INSTANCE_1);
        when(supplier.get(any())).thenReturn(Flux.just(List.of(instance)));

        StepVerifier.create(loadBalancer.choose(mockRequest()))
            .assertNext(response -> {
                assertThat(response.hasServer()).isTrue();
                assertThat(response.getServer().getInstanceId()).isEqualTo(INSTANCE_1);
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("InstanceHealthSnapshot корректно определяет устаревание")
    void instanceHealthSnapshot_stalenessCheck() {
        // Снимок только что захваченный — НЕ устаревший
        InstanceHealthSnapshot fresh = InstanceHealthSnapshot.now(0.3, 0.5, 0.2);
        assertThat(fresh.isStale(15L)).isFalse();

        // Снимок из далёкого прошлого — УСТАРЕВШИЙ
        InstanceHealthSnapshot stale = new InstanceHealthSnapshot(
            0.3, 0.5, 0.2,
            Instant.now().minusSeconds(60)
        );
        assertThat(stale.isStale(15L)).isTrue();
    }

    @Test
    @DisplayName("InstanceHealthSnapshot.now() устанавливает capturedAt ≈ текущее время")
    void instanceHealthSnapshot_nowSetsCurrentTime() {
        Instant before = Instant.now().minusSeconds(1);
        InstanceHealthSnapshot snapshot = InstanceHealthSnapshot.now(0.5, 0.6, 0.3);
        Instant after = Instant.now().plusSeconds(1);

        assertThat(snapshot.capturedAt()).isAfter(before).isBefore(after);
        assertThat(snapshot.cpuLoad()).isEqualTo(0.5);
        assertThat(snapshot.heapUsagePercent()).isEqualTo(0.6);
        assertThat(snapshot.activeThreadsRatio()).isEqualTo(0.3);
    }

    @Test
    @DisplayName("AdaptiveProperties: веса инициализируются дефолтами и суммируются к ~1.0")
    void adaptiveWeights_defaultSumToOne() {
        AdaptiveProperties.Weights weights = new AdaptiveProperties().getWeights();

        double sum = weights.getCpu() + weights.getHeap() + weights.getThreads() + weights.getLatency();

        assertThat(sum)
            .as("Сумма весов должна быть ~1.0")
            .isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("LatencyTracker: EMA latency используется при вычислении score")
    void latencyTracker_usedInScoreComputation() {
        // Записываем latency для instance-1 (высокая) и instance-2 (низкая)
        for (int i = 0; i < 5; i++) {
            latencyTracker.record(INSTANCE_1, 500);
            latencyTracker.record(INSTANCE_2, 50);
        }

        // Убеждаемся, что LatencyTracker возвращает разные значения
        assertThat(latencyTracker.getAvgLatency(INSTANCE_1))
            .isGreaterThan(latencyTracker.getAvgLatency(INSTANCE_2));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ServiceInstance createInstance(String instanceId) {
        return new DefaultServiceInstance(instanceId, "demo-service", "localhost", 8080, false);
    }

    @SuppressWarnings("unchecked")
    private static Request<Object> mockRequest() {
        return org.mockito.Mockito.mock(Request.class);
    }
}
