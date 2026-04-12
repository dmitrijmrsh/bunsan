package com.dmitrymrsh.bunsan.algorithm;

import com.dmitrymrsh.bunsan.starter.BunsanProperties.LeastConnectionsProperties;
import com.dmitrymrsh.bunsan.starter.BunsanProperties.LeastConnectionsProperties.TieBreaking;
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

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты для {@link LeastConnectionsLoadBalancer}.
 *
 * <p>Проверяет:
 * <ul>
 *   <li>выбор инстанса с минимальным числом соединений;</li>
 *   <li>корректный инкремент счётчика при выборе;</li>
 *   <li>стратегии разрешения ничьих (RANDOM, FIRST);</li>
 *   <li>empty response при пустом списке;</li>
 *   <li>независимость счётчиков разных инстансов.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class LeastConnectionsLoadBalancerTest {

    @Mock
    private ObjectProvider<ServiceInstanceListSupplier> supplierProvider;

    @Mock
    private ServiceInstanceListSupplier supplier;

    private ConnectionTracker connectionTracker;
    private LeastConnectionsProperties properties;
    private SimpleMeterRegistry meterRegistry;
    private LeastConnectionsLoadBalancer loadBalancer;

    private static final String SERVICE_ID = "demo-service";
    private static final String INSTANCE_1 = "instance-1";
    private static final String INSTANCE_2 = "instance-2";
    private static final String INSTANCE_3 = "instance-3";

    @BeforeEach
    void setUp() {
        connectionTracker = new ConnectionTracker();
        meterRegistry = new SimpleMeterRegistry();
        properties = new LeastConnectionsProperties();
        properties.setTieBreaking(TieBreaking.RANDOM);

        when(supplierProvider.getIfAvailable(any())).thenReturn(supplier);

        loadBalancer = new LeastConnectionsLoadBalancer(
            supplierProvider, SERVICE_ID, connectionTracker, meterRegistry, properties
        );
    }

    @Test
    @DisplayName("Пустой список → EmptyResponse")
    void emptyInstanceList_returnsEmptyResponse() {
        when(supplier.get(any())).thenReturn(Flux.just(List.of()));

        StepVerifier.create(loadBalancer.choose(mockRequest()))
            .assertNext(response -> assertThat(response.hasServer()).isFalse())
            .verifyComplete();
    }

    @Test
    @DisplayName("Один инстанс → выбирается он, счётчик инкрементируется")
    void singleInstance_chosenAndCounterIncremented() {
        ServiceInstance instance = createInstance(INSTANCE_1);
        when(supplier.get(any())).thenReturn(Flux.just(List.of(instance)));

        StepVerifier.create(loadBalancer.choose(mockRequest()))
            .assertNext(response -> {
                assertThat(response.hasServer()).isTrue();
                assertThat(response.getServer().getInstanceId()).isEqualTo(INSTANCE_1);
            })
            .verifyComplete();

        assertThat(connectionTracker.getInFlight(INSTANCE_1)).isEqualTo(1);
    }

    @Test
    @DisplayName("Выбирается инстанс с наименьшим числом соединений")
    void choosesInstanceWithLeastConnections() {
        ServiceInstance i1 = createInstance(INSTANCE_1);
        ServiceInstance i2 = createInstance(INSTANCE_2);

        // INSTANCE_1 — 5 активных соединений, INSTANCE_2 — 2
        IntStream.range(0, 5).forEach(__ -> connectionTracker.increment(INSTANCE_1));
        IntStream.range(0, 2).forEach(__ -> connectionTracker.increment(INSTANCE_2));

        when(supplier.get(any())).thenReturn(Flux.just(List.of(i1, i2)));

        StepVerifier.create(loadBalancer.choose(mockRequest()))
            .assertNext(response -> {
                assertThat(response.hasServer()).isTrue();
                assertThat(response.getServer().getInstanceId()).isEqualTo(INSTANCE_2);
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("При равных соединениях (TieBreaking=FIRST) → всегда первый инстанс")
    void tieBreakingFirst_alwaysChoosesFirstCandidate() {
        properties.setTieBreaking(TieBreaking.FIRST);
        loadBalancer = new LeastConnectionsLoadBalancer(
            supplierProvider, SERVICE_ID, connectionTracker, meterRegistry, properties
        );

        ServiceInstance i1 = createInstance(INSTANCE_1);
        ServiceInstance i2 = createInstance(INSTANCE_2);
        when(supplier.get(any())).thenReturn(Flux.just(List.of(i1, i2)));

        // Оба инстанса с 0 соединений → при FIRST всегда выбирается первый
        for (int i = 0; i < 5; i++) {
            // Декрементируем после каждого "запроса"
            StepVerifier.create(loadBalancer.choose(mockRequest()))
                .assertNext(response ->
                    assertThat(response.getServer().getInstanceId()).isEqualTo(INSTANCE_1))
                .verifyComplete();
            connectionTracker.decrement(INSTANCE_1);
        }
    }

    @Test
    @DisplayName("При равных соединениях (TieBreaking=RANDOM) → нагрузка распределяется между инстансами")
    void tieBreakingRandom_distributesLoadBetweenCandidates() {
        properties.setTieBreaking(TieBreaking.RANDOM);

        ServiceInstance i1 = createInstance(INSTANCE_1);
        ServiceInstance i2 = createInstance(INSTANCE_2);
        when(supplier.get(any())).thenReturn(Flux.just(List.of(i1, i2)));

        // Сбрасываем счётчики после каждого выбора (симулируем завершение запроса)
        Set<String> chosen = IntStream.range(0, 100)
            .mapToObj(__ -> {
                String id = loadBalancer.choose(mockRequest())
                    .map(r -> r.getServer().getInstanceId())
                    .block();
                connectionTracker.decrement(id);
                return id;
            })
            .collect(Collectors.toSet());

        // При случайном выборе оба инстанса должны встретиться
        assertThat(chosen)
            .as("TieBreaking=RANDOM должен выбирать разные инстансы")
            .containsExactlyInAnyOrder(INSTANCE_1, INSTANCE_2);
    }

    @Test
    @DisplayName("Счётчик декрементируется через ConnectionTracker.decrement()")
    void counterDecrementedAfterRelease() {
        ServiceInstance instance = createInstance(INSTANCE_1);
        when(supplier.get(any())).thenReturn(Flux.just(List.of(instance)));

        loadBalancer.choose(mockRequest()).block();
        assertThat(connectionTracker.getInFlight(INSTANCE_1)).isEqualTo(1);

        connectionTracker.decrement(INSTANCE_1);
        assertThat(connectionTracker.getInFlight(INSTANCE_1)).isEqualTo(0);
    }

    @Test
    @DisplayName("ConnectionTracker не уходит в отрицательные значения при лишнем декременте")
    void connectionTracker_noNegativeCount() {
        connectionTracker.decrement(INSTANCE_1); // не было инкремента
        assertThat(connectionTracker.getInFlight(INSTANCE_1)).isEqualTo(0);
    }

    @Test
    @DisplayName("Три инстанса: самый загруженный не выбирается")
    void threeInstances_mostLoadedSkipped() {
        ServiceInstance i1 = createInstance(INSTANCE_1);
        ServiceInstance i2 = createInstance(INSTANCE_2);
        ServiceInstance i3 = createInstance(INSTANCE_3);

        // i1=10, i2=5, i3=1 активных соединений
        IntStream.range(0, 10).forEach(__ -> connectionTracker.increment(INSTANCE_1));
        IntStream.range(0,  5).forEach(__ -> connectionTracker.increment(INSTANCE_2));
        IntStream.range(0,  1).forEach(__ -> connectionTracker.increment(INSTANCE_3));

        when(supplier.get(any())).thenReturn(Flux.just(List.of(i1, i2, i3)));

        StepVerifier.create(loadBalancer.choose(mockRequest()))
            .assertNext(response ->
                assertThat(response.getServer().getInstanceId()).isEqualTo(INSTANCE_3))
            .verifyComplete();
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
