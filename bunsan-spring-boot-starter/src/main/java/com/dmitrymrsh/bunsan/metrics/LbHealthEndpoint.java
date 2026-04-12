package com.dmitrymrsh.bunsan.metrics;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import com.sun.management.OperatingSystemMXBean;

/**
 * Кастомный Actuator-эндпоинт {@code /actuator/lb-health}.
 *
 * <p>Возвращает агрегированный JSON со сводкой метрик здоровья текущего JVM-процесса:
 * <pre>{@code
 * {
 *   "cpuLoad": 0.45,
 *   "heapUsagePercent": 0.72,
 *   "activeThreadsRatio": 0.30
 * }
 * }</pre>
 *
 * <p>Данные читаются из стандартных JVM MXBean (JMX) без блокирующих операций:
 * <ul>
 *   <li>{@code cpuLoad} — {@link OperatingSystemMXBean#getProcessCpuLoad()}</li>
 *   <li>{@code heapUsagePercent} — {@code heapUsed / heapMax}</li>
 *   <li>{@code activeThreadsRatio} — {@code threadCount / maxThreads}
 *       (с нормализацией к диапазону [0.0, 1.0]).</li>
 * </ul>
 *
 * <p>Эндпоинт опрашивается {@link com.dmitrymrsh.bunsan.algorithm.AdaptiveLoadBalancer}
 * каждые {@code bunsan.adaptive.metrics-poll-interval} секунд.
 * Чтобы эндпоинт был доступен снаружи, добавьте в {@code application.yml}:
 * <pre>{@code
 * management:
 *   endpoints:
 *     web:
 *       exposure:
 *         include: health,prometheus,lb-health
 * }</pre>
 */
@Endpoint(id = "lb-health")
public class LbHealthEndpoint {

    /**
     * Предполагаемое максимальное число потоков для нормализации {@code activeThreadsRatio}.
     * Соответствует значению по умолчанию {@code server.tomcat.threads.max = 200}.
     */
    private static final int DEFAULT_MAX_THREADS = 200;

    /**
     * Возвращает текущие метрики здоровья JVM-процесса.
     *
     * <p>Если {@code getProcessCpuLoad()} недоступен (возвращает {@code -1.0}),
     * поле {@code cpuLoad} устанавливается в {@code 0.0}.
     *
     * @return {@link LbHealthData} с метриками CPU, heap и потоков
     */
    @ReadOperation
    public LbHealthData health() {
        OperatingSystemMXBean osBean =
            ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

        // CPU load: -1.0 если недоступно → нормализуем к 0.0
        double cpuLoad = Math.max(0.0, osBean.getProcessCpuLoad());

        // Heap usage
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        double heapUsagePercent = heapUsage.getMax() > 0
            ? (double) heapUsage.getUsed() / heapUsage.getMax()
            : 0.0;

        // Active threads ratio (нормализованное к DEFAULT_MAX_THREADS)
        int liveThreads = threadBean.getThreadCount();
        double activeThreadsRatio = Math.min((double) liveThreads / DEFAULT_MAX_THREADS, 1.0);

        return new LbHealthData(cpuLoad, heapUsagePercent, activeThreadsRatio);
    }

    /**
     * DTO ответа эндпоинта {@code /actuator/lb-health}.
     *
     * @param cpuLoad            загрузка CPU процессом JVM (0.0–1.0)
     * @param heapUsagePercent   использование heap-памяти (0.0–1.0)
     * @param activeThreadsRatio отношение активных потоков к максимуму (0.0–1.0)
     */
    public record LbHealthData(
        double cpuLoad,
        double heapUsagePercent,
        double activeThreadsRatio
    ) {}
}
