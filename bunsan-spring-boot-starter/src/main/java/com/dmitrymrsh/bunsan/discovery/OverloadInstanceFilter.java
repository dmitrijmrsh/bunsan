package com.dmitrymrsh.bunsan.discovery;

import com.dmitrymrsh.bunsan.starter.BunsanProperties.DiscoveryProperties.OverloadFilterProperties;
import com.netflix.discovery.shared.Application;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.shared.Applications;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * AOP-аспект для фильтрации перегруженных инстансов на стороне Eureka Server.
 *
 * <h2>Принцип работы</h2>
 * Перехватывает вызовы метода {@code getApplications()} на бине
 * {@code PeerAwareInstanceRegistry} — это вызов происходит при обработке
 * HTTP-запросов от Eureka-клиентов к эндпоинту {@code /eureka/apps}.
 *
 * <p>Если {@code bunsan.discovery.overload-filter.enabled=true} (по умолчанию),
 * из возвращаемого объекта {@link Applications} исключаются все инстансы
 * со статусом {@link InstanceInfo.InstanceStatus#OUT_OF_SERVICE}.
 *
 * <p>Инстансы переходят в {@code OUT_OF_SERVICE} автоматически через механизм
 * Eureka heartbeat: {@link OverloadHealthIndicator} на стороне сервиса возвращает
 * {@code Health.outOfService()}, и Eureka-клиент транслирует это в статус heartbeat.
 *
 * <h2>Восстановление инстанса</h2>
 * Когда нагрузка снижается ниже порогов, {@link OverloadHealthIndicator} возвращает
 * {@code Health.up()} → Eureka-клиент отправляет heartbeat с {@code UP} →
 * инстанс снова появляется в ответах реестра.
 * Задержка восстановления ≈ {@code eureka.instance.lease-renewal-interval-in-seconds}.
 *
 * <h2>Метрики</h2>
 * Каждая фильтрация инстанса инкрементирует счётчик
 * {@code bunsan_instance_filtered_total{instance_id}}.
 *
 * <p><b>Создаётся только при наличии Eureka Server в classpath</b> —
 * через {@code @ConditionalOnClass} в {@link com.dmitrymrsh.bunsan.starter.BunsanAutoConfiguration}.
 */
@Aspect
public class OverloadInstanceFilter {

    private static final Logger log = LoggerFactory.getLogger(OverloadInstanceFilter.class);

    private final OverloadFilterProperties properties;
    private final MeterRegistry meterRegistry;

    /**
     * Создаёт фильтр с заданными параметрами.
     *
     * @param properties    настройки фильтра (enabled, пороги)
     * @param meterRegistry реестр метрик для публикации счётчика фильтраций
     */
    public OverloadInstanceFilter(OverloadFilterProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Перехватывает вызовы {@code getApplications()} на Eureka-реестре.
     *
     * <p>Pointcut охватывает реализацию метода в {@code PeerAwareInstanceRegistryImpl},
     * которая вызывается при обработке {@code GET /eureka/apps} от клиентов.
     *
     * @param pjp контекст перехваченного вызова
     * @return отфильтрованный {@link Applications} (или оригинальный при disabled)
     * @throws Throwable при ошибке в оригинальном методе
     */
    @Around("execution(* com.netflix.eureka.registry.PeerAwareInstanceRegistry+.getApplications(..))")
    public Object filterOverloadedInstances(ProceedingJoinPoint pjp) throws Throwable {
        Applications applications = (Applications) pjp.proceed();

        if (!properties.isEnabled() || applications == null) {
            return applications;
        }

        return buildFilteredApplications(applications);
    }

    /**
     * Строит новый объект {@link Applications} без {@code OUT_OF_SERVICE} инстансов.
     *
     * <p>Создаётся новый {@link Applications} с перенесёнными приложениями,
     * из которых исключены перегруженные инстансы. После фильтрации
     * вызывается {@link Applications#reconcileHashCode()} для пересчёта хэша реестра.
     *
     * @param original исходный объект реестра
     * @return отфильтрованный реестр
     */
    private Applications buildFilteredApplications(Applications original) {
        Applications filtered = new Applications();
        int totalFiltered = 0;

        for (Application originalApp : original.getRegisteredApplications()) {
            Application filteredApp = new Application(originalApp.getName());

            for (InstanceInfo instance : originalApp.getInstances()) {
                if (instance.getStatus() == InstanceInfo.InstanceStatus.OUT_OF_SERVICE) {
                    // Инстанс перегружен — исключаем и инкрементируем счётчик
                    incrementFilteredCounter(instance.getInstanceId());
                    totalFiltered++;
                    log.debug("Filtering overloaded instance: appName={} instanceId={}",
                        originalApp.getName(), instance.getInstanceId());
                } else {
                    filteredApp.addInstance(instance);
                }
            }

            // Добавляем приложение в реестр только если в нём остались инстансы
            if (!filteredApp.getInstances().isEmpty()) {
                filtered.addApplication(filteredApp);
            }
        }

        if (totalFiltered > 0) {
            log.info("Overload filter: excluded {} OUT_OF_SERVICE instance(s) from registry response",
                totalFiltered);
        }

        // Пересчитываем хэш реестра после изменений (используется для
        // incremental registry sync между Eureka-клиентами и сервером)
        filtered.setAppsHashCode(filtered.getReconcileHashCode());

        return filtered;
    }

    /**
     * Инкрементирует счётчик Micrometer для данного инстанса.
     * Gauge-метрика видна в Prometheus как {@code bunsan_instance_filtered_total}.
     *
     * @param instanceId идентификатор отфильтрованного инстанса
     */
    private void incrementFilteredCounter(String instanceId) {
        meterRegistry.counter(
            "bunsan_instance_filtered_total",
            List.of(Tag.of("instance_id", instanceId))
        ).increment();
    }
}
