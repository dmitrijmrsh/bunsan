package com.dmitrymrsh.bunsan.simulation;

/**
 * Симуляция адаптивного алгоритма балансировки.
 *
 * <p>Ожидаемое поведение:
 * <ul>
 *   <li>В первые ~15 сек (до первого опроса /actuator/lb-health): штрафной score →
 *       равномерное распределение.</li>
 *   <li>В стабильной фазе: балансировщик смещает трафик к здоровым инстансам по composite score
 *       (CPU + heap + threads + latency).</li>
 *   <li>В фазе деградации: demo-service-4 получает высокий score → трафик уходит к demo-service-1..3.</li>
 *   <li>Если demo-service-4 переходит в OUT_OF_SERVICE (через OverloadInstanceFilter) —
 *       он полностью исчезает из реестра Eureka → никакого трафика на него.</li>
 *   <li>В фазе наблюдения: после снижения CPU инстанс восстанавливается и снова получает трафик.</li>
 * </ul>
 *
 * <p>Запуск:
 * <pre>
 *   mvn gatling:test -pl load-tests \
 *     -Dgatling.simulationClass=com.dmitrymrsh.bunsan.simulation.AdaptiveSimulation \
 *     -Dgatling.baseUrl=http://localhost:8080
 * </pre>
 *
 * <p>demo-gateway должен быть запущен с {@code BUNSAN_ALGORITHM=adaptive}
 */
public class AdaptiveSimulation extends BunsanBaseSimulation {

    public AdaptiveSimulation() {
        runSimulation();
    }

    @Override
    protected String strategyName() {
        return "adaptive";
    }
}
