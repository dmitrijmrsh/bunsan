package com.dmitrymrsh.bunsan.starter;

import com.dmitrymrsh.bunsan.algorithm.AdaptiveLoadBalancer;
import com.dmitrymrsh.bunsan.algorithm.ConnectionTracker;
import com.dmitrymrsh.bunsan.algorithm.LatencyTracker;
import com.dmitrymrsh.bunsan.algorithm.LeastConnectionsLoadBalancer;
import com.dmitrymrsh.bunsan.algorithm.WeightedResponseTimeLoadBalancer;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.cloud.loadbalancer.core.RandomLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer;
import org.springframework.cloud.loadbalancer.core.RoundRobinLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Конфигурация балансировщика нагрузки, создаваемая для каждого клиента (сервиса) отдельно.
 *
 * <p>Этот класс регистрируется как {@code defaultConfiguration} в аннотации
 * {@link LoadBalancerClients @LoadBalancerClients} на {@link BunsanAutoConfiguration}.
 * Spring Cloud LoadBalancer создаёт для каждого имени сервиса собственный дочерний
 * ApplicationContext и добавляет в него данную конфигурацию.
 *
 * <p><b>Внимание:</b> этот класс НЕ должен сканироваться через {@code @ComponentScan} —
 * его жизненным циклом управляет {@link LoadBalancerClientFactory}.
 *
 * <p>Beans из родительского контекста ({@link LatencyTracker}, {@link ConnectionTracker},
 * {@link MeterRegistry}) доступны в дочернем контексте через механизм наследования
 * ApplicationContext.
 */
@Configuration(proxyBeanMethods = false)
public class BunsanClientConfiguration {

    /**
     * Создаёт бин балансировщика нагрузки для конкретного сервиса.
     *
     * <p>Выбор алгоритма определяется свойством {@code bunsan.algorithm}.
     * Если алгоритм не задан явно — используется {@code ROUND_ROBIN}.
     *
     * @param environment                         окружение Spring (содержит имя текущего клиента)
     * @param loadBalancerClientFactory           фабрика клиентских контекстов LB
     * @param properties                          настройки стартера
     * @param latencyTrackerProvider              провайдер разделяемого LatencyTracker
     * @param connectionTrackerProvider           провайдер разделяемого ConnectionTracker
     * @param meterRegistryProvider               провайдер реестра метрик
     * @return реализация {@link ReactorLoadBalancer} для данного сервиса
     */
    @Bean
    public ReactorLoadBalancer<ServiceInstance> reactorServiceInstanceLoadBalancer(
        Environment environment,
        LoadBalancerClientFactory loadBalancerClientFactory,
        BunsanProperties properties,
        ObjectProvider<LatencyTracker> latencyTrackerProvider,
        ObjectProvider<ConnectionTracker> connectionTrackerProvider,
        ObjectProvider<MeterRegistry> meterRegistryProvider
    ) {
        String serviceId = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
        ObjectProvider<ServiceInstanceListSupplier> supplierProvider =
            loadBalancerClientFactory.getLazyProvider(serviceId, ServiceInstanceListSupplier.class);

        return switch (properties.getAlgorithm()) {

            case WEIGHTED_RESPONSE_TIME -> new WeightedResponseTimeLoadBalancer(
                supplierProvider,
                serviceId,
                latencyTrackerProvider.getObject(),
                properties.getWeightedResponseTime()
            );

            case LEAST_CONNECTIONS -> new LeastConnectionsLoadBalancer(
                supplierProvider,
                serviceId,
                connectionTrackerProvider.getObject(),
                meterRegistryProvider.getObject(),
                properties.getLeastConnections()
            );

            case ADAPTIVE -> new AdaptiveLoadBalancer(
                supplierProvider,
                serviceId,
                latencyTrackerProvider.getObject(),
                meterRegistryProvider.getObject(),
                properties.getAdaptive()
            );

            case RANDOM -> new RandomLoadBalancer(supplierProvider, serviceId);

            // Дефолт: ROUND_ROBIN — стандартный алгоритм Spring Cloud LoadBalancer
            default -> new RoundRobinLoadBalancer(supplierProvider, serviceId);
        };
    }
}
