package com.dmitrymrsh.bunsan.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Единый источник истины для всех настроек bunsan-spring-boot-starter.
 *
 * <p>Все свойства живут под префиксом {@code bunsan.*} и привязываются
 * через механизм {@link ConfigurationProperties @ConfigurationProperties}.
 *
 * <p>Пример конфигурации в {@code application.yml}:
 * <pre>{@code
 * bunsan:
 *   algorithm: weighted-response-time
 *   weighted-response-time:
 *     ema-alpha: 0.3
 *     window-size: 5
 *   adaptive:
 *     metrics-poll-interval: 5s
 *     weights:
 *       cpu: 0.35
 *       heap: 0.25
 *       threads: 0.20
 *       latency: 0.20
 *   discovery:
 *     overload-filter:
 *       enabled: true
 *       cpu-threshold: 0.80
 *       heap-threshold: 0.85
 * }</pre>
 */
@ConfigurationProperties(prefix = "bunsan")
public class BunsanProperties {

    /** Алгоритм балансировки нагрузки. По умолчанию: {@code round-robin}. */
    private Algorithm algorithm = Algorithm.ROUND_ROBIN;

    private WeightedResponseTimeProperties weightedResponseTime = new WeightedResponseTimeProperties();
    private LeastConnectionsProperties leastConnections = new LeastConnectionsProperties();
    private AdaptiveProperties adaptive = new AdaptiveProperties();
    private DiscoveryProperties discovery = new DiscoveryProperties();

    // -------------------------------------------------------------------------
    // Getters / Setters
    // -------------------------------------------------------------------------

    public Algorithm getAlgorithm() { return algorithm; }
    public void setAlgorithm(Algorithm algorithm) { this.algorithm = algorithm; }

    public WeightedResponseTimeProperties getWeightedResponseTime() { return weightedResponseTime; }
    public void setWeightedResponseTime(WeightedResponseTimeProperties p) { this.weightedResponseTime = p; }

    public LeastConnectionsProperties getLeastConnections() { return leastConnections; }
    public void setLeastConnections(LeastConnectionsProperties p) { this.leastConnections = p; }

    public AdaptiveProperties getAdaptive() { return adaptive; }
    public void setAdaptive(AdaptiveProperties p) { this.adaptive = p; }

    public DiscoveryProperties getDiscovery() { return discovery; }
    public void setDiscovery(DiscoveryProperties p) { this.discovery = p; }

    // =========================================================================
    // Enum: алгоритм
    // =========================================================================

    /**
     * Перечисление доступных алгоритмов балансировки.
     * Spring Boot автоматически сопоставляет значения из properties
     * (dash-case или UPPER_CASE) с элементами enum.
     */
    public enum Algorithm {
        /** Взвешенное среднее времени ответа (EMA). */
        WEIGHTED_RESPONSE_TIME,
        /** Наименьшее число активных соединений. */
        LEAST_CONNECTIONS,
        /** Адаптивный балансировщик на основе CPU/heap/threads/latency. */
        ADAPTIVE,
        /** Классический round-robin (поставляется Spring Cloud). */
        ROUND_ROBIN,
        /** Случайный выбор инстанса (поставляется Spring Cloud). */
        RANDOM
    }

    // =========================================================================
    // Nested: WeightedResponseTime
    // =========================================================================

    /**
     * Настройки алгоритма {@link Algorithm#WEIGHTED_RESPONSE_TIME}.
     */
    public static class WeightedResponseTimeProperties {

        /**
         * Коэффициент сглаживания экспоненциального скользящего среднего (EMA).
         * Чем выше значение — тем сильнее влияние последних замеров.
         * Допустимый диапазон: (0.0, 1.0). По умолчанию: {@code 0.3}.
         */
        private double emaAlpha = 0.3;

        /**
         * Минимальное число запросов к инстансу, после которого активируется
         * взвешенный выбор. До достижения порога используется round-robin.
         * По умолчанию: {@code 5}.
         */
        private int windowSize = 5;

        public double getEmaAlpha() { return emaAlpha; }
        public void setEmaAlpha(double emaAlpha) { this.emaAlpha = emaAlpha; }

        public int getWindowSize() { return windowSize; }
        public void setWindowSize(int windowSize) { this.windowSize = windowSize; }
    }

    // =========================================================================
    // Nested: LeastConnections
    // =========================================================================

    /**
     * Настройки алгоритма {@link Algorithm#LEAST_CONNECTIONS}.
     */
    public static class LeastConnectionsProperties {

        /**
         * Стратегия выбора при нескольких инстансах с одинаковым
         * числом активных соединений (tie-breaking).
         * По умолчанию: {@code RANDOM}.
         */
        private TieBreaking tieBreaking = TieBreaking.RANDOM;

        public TieBreaking getTieBreaking() { return tieBreaking; }
        public void setTieBreaking(TieBreaking tieBreaking) { this.tieBreaking = tieBreaking; }

        /** Стратегия разрешения ничьих при равном числе соединений. */
        public enum TieBreaking {
            /** Выбрать случайный инстанс среди минимальных. */
            RANDOM,
            /** Выбрать первый инстанс в списке среди минимальных. */
            FIRST
        }
    }

    // =========================================================================
    // Nested: Adaptive
    // =========================================================================

    /**
     * Настройки алгоритма {@link Algorithm#ADAPTIVE}.
     */
    public static class AdaptiveProperties {

        /**
         * Интервал опроса эндпоинта {@code /actuator/lb-health} каждого инстанса.
         * По умолчанию: {@code 5s}.
         */
        private Duration metricsPollInterval = Duration.ofSeconds(5);

        /**
         * Веса компонент в формуле составного score.
         * Сумма весов должна равняться 1.0.
         */
        private Weights weights = new Weights();

        public Duration getMetricsPollInterval() { return metricsPollInterval; }
        public void setMetricsPollInterval(Duration metricsPollInterval) {
            this.metricsPollInterval = metricsPollInterval;
        }

        public Weights getWeights() { return weights; }
        public void setWeights(Weights weights) { this.weights = weights; }

        /**
         * Веса компонент формулы:
         * {@code score = w_cpu*cpu + w_heap*heap + w_threads*threads + w_latency*normalizedLatency}.
         * Инстанс с наименьшим score получает следующий запрос.
         */
        public static class Weights {
            /** Вес загрузки CPU. По умолчанию: {@code 0.35}. */
            private double cpu = 0.35;
            /** Вес использования heap. По умолчанию: {@code 0.25}. */
            private double heap = 0.25;
            /** Вес активных потоков. По умолчанию: {@code 0.20}. */
            private double threads = 0.20;
            /** Вес нормализованной latency. По умолчанию: {@code 0.20}. */
            private double latency = 0.20;

            public double getCpu() { return cpu; }
            public void setCpu(double cpu) { this.cpu = cpu; }

            public double getHeap() { return heap; }
            public void setHeap(double heap) { this.heap = heap; }

            public double getThreads() { return threads; }
            public void setThreads(double threads) { this.threads = threads; }

            public double getLatency() { return latency; }
            public void setLatency(double latency) { this.latency = latency; }
        }
    }

    // =========================================================================
    // Nested: Discovery / OverloadFilter
    // =========================================================================

    /**
     * Настройки, связанные с Service Discovery (Eureka).
     */
    public static class DiscoveryProperties {

        private OverloadFilterProperties overloadFilter = new OverloadFilterProperties();

        public OverloadFilterProperties getOverloadFilter() { return overloadFilter; }
        public void setOverloadFilter(OverloadFilterProperties p) { this.overloadFilter = p; }

        /**
         * Настройки фильтра перегруженных инстансов.
         * Фильтр работает на стороне Eureka Server и исключает из реестра
         * инстансы со статусом {@code OUT_OF_SERVICE}.
         */
        public static class OverloadFilterProperties {

            /**
             * Включить/выключить фильтрацию перегруженных инстансов.
             * При значении {@code false} реестр отдаётся без изменений.
             * По умолчанию: {@code true}.
             */
            private boolean enabled = true;

            /**
             * Порог загрузки CPU (0.0–1.0), при превышении которого
             * инстанс переходит в статус {@code OUT_OF_SERVICE}.
             * По умолчанию: {@code 0.80} (80%).
             */
            private double cpuThreshold = 0.80;

            /**
             * Порог использования heap (0.0–1.0), при превышении которого
             * инстанс переходит в статус {@code OUT_OF_SERVICE}.
             * По умолчанию: {@code 0.85} (85%).
             */
            private double heapThreshold = 0.85;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }

            public double getCpuThreshold() { return cpuThreshold; }
            public void setCpuThreshold(double cpuThreshold) { this.cpuThreshold = cpuThreshold; }

            public double getHeapThreshold() { return heapThreshold; }
            public void setHeapThreshold(double heapThreshold) { this.heapThreshold = heapThreshold; }
        }
    }
}
