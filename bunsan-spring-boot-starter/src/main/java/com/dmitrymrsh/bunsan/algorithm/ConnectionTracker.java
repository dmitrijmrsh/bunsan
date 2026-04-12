package com.dmitrymrsh.bunsan.algorithm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Разделяемый компонент для отслеживания числа активных (in-flight) запросов
 * по каждому инстансу сервиса.
 *
 * <p>Используется алгоритмом {@link LeastConnectionsLoadBalancer}: при выборе инстанса
 * счётчик инкрементируется, при завершении запроса — декрементируется.
 * Декремент производится в {@code LatencyTrackingGlobalFilter.doFinally()},
 * что гарантирует выполнение при любом исходе: успехе, ошибке или отмене.
 *
 * <p>Важное ограничение: счётчики ведутся <em>на стороне клиента</em> (gateway).
 * Если несколько gateway-инстансов работают параллельно, каждый ведёт
 * свои счётчики независимо, не зная о счётчиках других gateway.
 *
 * <p>Все операции потокобезопасны — используются {@link AtomicInteger}.
 */
public class ConnectionTracker {

    private static final Logger log = LoggerFactory.getLogger(ConnectionTracker.class);

    /** Счётчики активных запросов: instanceId → in-flight count. */
    private final ConcurrentHashMap<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    /**
     * Инкрементирует счётчик активных запросов для инстанса.
     * Вызывается балансировщиком в момент выбора инстанса.
     *
     * @param instanceId идентификатор выбранного инстанса
     * @return обновлённое значение счётчика
     */
    public int increment(String instanceId) {
        int value = inFlight
            .computeIfAbsent(instanceId, k -> new AtomicInteger(0))
            .incrementAndGet();
        log.trace("Connection incremented: instanceId={} inFlight={}", instanceId, value);
        return value;
    }

    /**
     * Декрементирует счётчик активных запросов для инстанса.
     * Вызывается из {@code LatencyTrackingGlobalFilter.doFinally()} после
     * завершения запроса (успех, ошибка или отмена).
     *
     * <p>Счётчик не опускается ниже 0: это защита от дисбаланса при
     * некорректном порядке вызовов (например, при перезапуске gateway).
     *
     * @param instanceId идентификатор инстанса
     * @return обновлённое значение счётчика
     */
    public int decrement(String instanceId) {
        AtomicInteger counter = inFlight.get(instanceId);
        if (counter == null) {
            log.warn("Decrement called for unknown instanceId={}", instanceId);
            return 0;
        }
        // Не допускаем отрицательных значений
        int value = counter.updateAndGet(current -> Math.max(0, current - 1));
        log.trace("Connection decremented: instanceId={} inFlight={}", instanceId, value);
        return value;
    }

    /**
     * Возвращает текущее число активных запросов к инстансу.
     *
     * @param instanceId идентификатор инстанса
     * @return число in-flight запросов; {@code 0} если инстанс неизвестен
     */
    public int getInFlight(String instanceId) {
        AtomicInteger counter = inFlight.get(instanceId);
        return counter != null ? counter.get() : 0;
    }
}
