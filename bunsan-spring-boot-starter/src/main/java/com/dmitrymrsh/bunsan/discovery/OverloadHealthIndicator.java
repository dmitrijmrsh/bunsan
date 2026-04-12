package com.dmitrymrsh.bunsan.discovery;

import com.dmitrymrsh.bunsan.starter.BunsanProperties.DiscoveryProperties.OverloadFilterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import com.sun.management.OperatingSystemMXBean;

/**
 * Health indicator для автоматического перевода инстанса в статус {@code OUT_OF_SERVICE}
 * при превышении пороговых значений нагрузки.
 *
 * <h2>Принцип работы</h2>
 * При каждом вызове {@link #health()} проверяются текущие значения:
 * <ul>
 *   <li>CPU load (через {@link OperatingSystemMXBean#getProcessCpuLoad()});</li>
 *   <li>heap usage ({@code heapUsed / heapMax}).</li>
 * </ul>
 *
 * <p>Если хотя бы одна метрика превышает настроенный порог —
 * возвращается {@link Health#outOfService()}. В штатном режиме — {@link Health#up()}.
 *
 * <h2>Интеграция с Eureka</h2>
 * Spring Boot Actuator автоматически агрегирует все {@link HealthIndicator}-бины.
 * Eureka-клиент ({@code HealthCheckHandler}) периодически проверяет агрегированный
 * статус и транслирует его в поле {@code status} heartbeat на Eureka Server.
 * Таким образом, при {@code OUT_OF_SERVICE} → Eureka Server помечает инстанс как
 * недоступный, и {@link OverloadInstanceFilter} исключит его из ответов реестра.
 *
 * <h2>Восстановление</h2>
 * Когда нагрузка падает ниже порогов, {@link #health()} вновь возвращает {@link Health#up()}.
 * Задержка восстановления определяется интервалом heartbeat:
 * {@code eureka.instance.lease-renewal-interval-in-seconds} (10 секунд в демо-конфигурации).
 *
 * <p>Порог CPU задаётся через {@code bunsan.discovery.overload-filter.cpu-threshold},
 * порог heap — через {@code bunsan.discovery.overload-filter.heap-threshold}.
 *
 * <p><b>Создаётся только при наличии Eureka Client в classpath</b>.
 */
public class OverloadHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(OverloadHealthIndicator.class);

    private final OverloadFilterProperties properties;

    /**
     * Создаёт индикатор здоровья с заданными пороговыми значениями.
     *
     * @param properties настройки фильтра перегрузки (пороги CPU и heap)
     */
    public OverloadHealthIndicator(OverloadFilterProperties properties) {
        this.properties = properties;
    }

    /**
     * Проверяет текущую нагрузку и возвращает статус здоровья инстанса.
     *
     * <p>Детали ({@code withDetail}) используются для диагностики и видны
     * в {@code /actuator/health} в режиме {@code management.endpoint.health.show-details=always}.
     *
     * @return {@link Health#outOfService()} при перегрузке; {@link Health#up()} в норме
     */
    @Override
    public Health health() {
        OperatingSystemMXBean osBean =
            ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

        double cpuLoad = osBean.getProcessCpuLoad();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        double heapUsagePercent = heapUsage.getMax() > 0
            ? (double) heapUsage.getUsed() / heapUsage.getMax()
            : 0.0;

        // cpuLoad == -1.0 означает «недоступно» — не считаем перегрузкой
        boolean cpuOverloaded  = cpuLoad >= 0 && cpuLoad > properties.getCpuThreshold();
        boolean heapOverloaded = heapUsagePercent > properties.getHeapThreshold();

        if (cpuOverloaded || heapOverloaded) {
            log.warn("Instance is OVERLOADED: cpuLoad={:.2f} cpuThreshold={} heapUsage={:.2f} heapThreshold={}",
                cpuLoad, properties.getCpuThreshold(), heapUsagePercent, properties.getHeapThreshold());

            return Health.outOfService()
                .withDetail("reason",           buildReason(cpuOverloaded, heapOverloaded))
                .withDetail("cpuLoad",          cpuLoad)
                .withDetail("cpuThreshold",     properties.getCpuThreshold())
                .withDetail("heapUsagePercent", heapUsagePercent)
                .withDetail("heapThreshold",    properties.getHeapThreshold())
                .build();
        }

        return Health.up()
            .withDetail("cpuLoad",          cpuLoad)
            .withDetail("heapUsagePercent", heapUsagePercent)
            .build();
    }

    /**
     * Формирует описание причины перегрузки для поля {@code reason} в Health details.
     */
    private String buildReason(boolean cpuOverloaded, boolean heapOverloaded) {
        if (cpuOverloaded && heapOverloaded) return "CPU and heap overloaded";
        if (cpuOverloaded)  return "CPU overloaded";
        return "Heap overloaded";
    }
}
