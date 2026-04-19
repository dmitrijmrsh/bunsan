package com.dmitrymrsh.bunsan.algorithm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.dmitrymrsh.bunsan.starter.BunsanProperties.AdaptiveProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
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
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Адаптивный балансировщик нагрузки на основе составного score.
 *
 * <h2>Формула score</h2>
 * <pre>
 * score = w_cpu * cpuLoad
 *       + w_heap * heapUsagePercent
 *       + w_threads * activeThreadsRatio
 *       + w_latency * normalizedLatency
 * </pre>
 * Веса {@code w_*} настраиваются через {@code bunsan.adaptive.weights.*}.
 * Выбирается инстанс с <em>наименьшим</em> score.
 *
 * <h2>Сбор метрик здоровья</h2>
 * Фоновый планировщик (virtual thread) каждые {@code N} секунд опрашивает
 * эндпоинт {@code /actuator/lb-health} каждого известного инстанса через
 * неблокирующий {@link WebClient}. Результаты кэшируются как {@link InstanceHealthSnapshot}.
 *
 * <h2>Устаревшие данные</h2>
 * Если снимок метрик инстанса старше {@code 3 × metricsPollInterval} —
 * инстанс получает штрафной score {@code 1.0} (максимальный), что снижает
 * вероятность его выбора до прихода свежих данных.
 *
 * <p><b>Блокирующие вызовы запрещены</b> — {@link #choose(Request)} реактивен.
 */
public class AdaptiveLoadBalancer implements ReactorServiceInstanceLoadBalancer {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveLoadBalancer.class);

    /** Штрафной score при устаревших или отсутствующих метриках. */
    private static final double PENALTY_SCORE = 1.0;

    /** Максимальная latency для нормализации (мс). */
    private static final double MAX_LATENCY_MS = 5000.0;

    private final ObjectProvider<ServiceInstanceListSupplier> serviceInstanceListSupplierProvider;
    private final String serviceId;
    private final LatencyTracker latencyTracker;
    private final MeterRegistry meterRegistry;
    private final AdaptiveProperties properties;

    /** Клиент для опроса /actuator/lb-health. */
    private final WebClient webClient;

    /** Кэш снимков метрик: instanceId → последний InstanceHealthSnapshot. */
    private final ConcurrentHashMap<String, InstanceHealthSnapshot> healthCache = new ConcurrentHashMap<>();

    /**
     * Кэш score для публикации gauge-метрик: instanceId → AtomicReference<Double>.
     * AtomicReference хранится как strong reference — Micrometer не соберёт его GC.
     */
    private final ConcurrentHashMap<String, AtomicReference<Double>> scoreRefs = new ConcurrentHashMap<>();

    /** Список инстансов из последнего вызова choose(). Используется фоновым планировщиком. */
    private volatile List<ServiceInstance> knownInstances = List.of();

    /**
     * Планировщик на virtual thread — опрашивает /actuator/lb-health периодически.
     * Virtual threads (Java 21+) позволяют держать сотни потоков без накладных расходов.
     */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
        1,
        Thread.ofVirtual().name("adaptive-lb-poller-", 0).factory()
    );

    /**
     * Создаёт адаптивный балансировщик и запускает фоновый планировщик опроса метрик.
     *
     * @param serviceInstanceListSupplierProvider поставщик инстансов
     * @param serviceId                           имя сервиса
     * @param latencyTracker                      разделяемый трекер EMA-latency
     * @param meterRegistry                       реестр метрик Micrometer
     * @param properties                          настройки алгоритма (веса, интервал опроса)
     */
    public AdaptiveLoadBalancer(
        ObjectProvider<ServiceInstanceListSupplier> serviceInstanceListSupplierProvider,
        String serviceId,
        LatencyTracker latencyTracker,
        MeterRegistry meterRegistry,
        AdaptiveProperties properties
    ) {
        this.serviceInstanceListSupplierProvider = serviceInstanceListSupplierProvider;
        this.serviceId = serviceId;
        this.latencyTracker = latencyTracker;
        this.meterRegistry = meterRegistry;
        this.properties = properties;
        this.webClient = WebClient.builder()
            .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024))
            .build();

        long intervalMs = properties.getMetricsPollInterval().toMillis();
        scheduler.scheduleAtFixedRate(
            this::pollAllInstances,
            intervalMs,
            intervalMs,
            TimeUnit.MILLISECONDS
        );

        log.info("AdaptiveLoadBalancer started for serviceId={} pollInterval={}ms",
            serviceId, intervalMs);
    }

    /**
     * Выбирает инстанс с наименьшим составным score.
     *
     * <p>После выбора обновляет список известных инстансов для фонового опроса.
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
                // Обновляем список для фонового опроса
                this.knownInstances = instances;
                return pickBestInstance(instances);
            });
    }

    /**
     * Выбирает инстанс с наименьшим score и публикует метрики для всех инстансов.
     *
     * <p>Для каждого инстанса лениво создаётся {@link AtomicReference} и регистрируется
     * gauge, указывающий на него. Это гарантирует, что Micrometer не соберёт объект GC
     * (в отличие от передачи {@code double} напрямую) и что все инстансы видны в Grafana,
     * а не только выбранный.
     */
    private Response<ServiceInstance> pickBestInstance(List<ServiceInstance> instances) {
        long staleness = properties.getMetricsPollInterval().toSeconds() * 3L;

        ServiceInstance chosen = instances.stream()
            .min(Comparator.comparingDouble(i -> computeScore(i, staleness)))
            .orElse(instances.get(0));

        // Публикуем score для всех инстансов
        for (ServiceInstance instance : instances) {
            String instanceId = instance.getInstanceId();
            double score = computeScore(instance, staleness);

            AtomicReference<Double> ref = scoreRefs.computeIfAbsent(instanceId, id -> {
                AtomicReference<Double> newRef = new AtomicReference<>(score);
                meterRegistry.gauge(
                    "bunsan_instance_score",
                    List.of(Tag.of("instance_id", id)),
                    newRef,
                    AtomicReference::get
                );
                return newRef;
            });
            ref.set(score);
        }

        log.debug("Chosen instance: serviceId={} instanceId={} score={}",
            serviceId, chosen.getInstanceId(), computeScore(chosen, staleness));

        return new DefaultResponse(chosen);
    }

    /**
     * Вычисляет составной score для инстанса.
     *
     * <p>Если снимок метрик устарел или отсутствует — возвращает штрафное значение {@link #PENALTY_SCORE}.
     *
     * @param instance   инстанс
     * @param stalenessSec порог устаревания снимка в секундах
     * @return score ∈ [0.0, 1.0], меньше — лучше
     */
    private double computeScore(ServiceInstance instance, long stalenessSec) {
        String id = instance.getInstanceId();
        InstanceHealthSnapshot snapshot = healthCache.get(id);

        if (snapshot == null || snapshot.isStale(stalenessSec)) {
            log.debug("Stale/missing health snapshot for instanceId={} — applying penalty", id);
            return PENALTY_SCORE;
        }

        AdaptiveProperties.Weights w = properties.getWeights();
        double normalizedLatency = Math.min(latencyTracker.getAvgLatency(id) / MAX_LATENCY_MS, 1.0);

        return w.getCpu()     * snapshot.cpuLoad()
             + w.getHeap()    * snapshot.heapUsagePercent()
             + w.getThreads() * snapshot.activeThreadsRatio()
             + w.getLatency() * normalizedLatency;
    }

    /**
     * Асинхронно опрашивает {@code /actuator/lb-health} для всех известных инстансов.
     * Вызывается фоновым планировщиком на virtual thread.
     */
    private void pollAllInstances() {
        List<ServiceInstance> instances = knownInstances;
        for (ServiceInstance instance : instances) {
            pollInstance(instance);
        }
    }

    /**
     * Неблокирующий запрос к {@code /actuator/lb-health} инстанса.
     * Результат кэшируется в {@link #healthCache}.
     */
    private void pollInstance(ServiceInstance instance) {
        String instanceId = instance.getInstanceId();
        String url = "http://" + instance.getHost() + ":" + instance.getPort() + "/actuator/lb-health";

        webClient.get()
            .uri(url)
            .retrieve()
            .bodyToMono(LbHealthResponse.class)
            .timeout(Duration.ofSeconds(2))
            .doOnNext(response -> {
                InstanceHealthSnapshot snapshot = InstanceHealthSnapshot.now(
                    response.cpuLoad(),
                    response.heapUsagePercent(),
                    response.activeThreadsRatio()
                );
                healthCache.put(instanceId, snapshot);
                log.trace("Health polled: instanceId={} cpu={} heap={} threads={}",
                    instanceId, response.cpuLoad(), response.heapUsagePercent(), response.activeThreadsRatio());
            })
            .doOnError(e -> log.warn("Failed to poll lb-health for instanceId={}: {}", instanceId, e.getMessage()))
            .onErrorComplete()
            .subscribe();
    }

    /**
     * DTO для десериализации ответа от {@code /actuator/lb-health}.
     *
     * @param cpuLoad            загрузка CPU (0.0–1.0)
     * @param heapUsagePercent   использование heap (0.0–1.0)
     * @param activeThreadsRatio отношение активных потоков (0.0–1.0)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record LbHealthResponse(
        @JsonProperty("cpuLoad")            double cpuLoad,
        @JsonProperty("heapUsagePercent")   double heapUsagePercent,
        @JsonProperty("activeThreadsRatio") double activeThreadsRatio
    ) {}

    // Заглушка-поставщик
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
