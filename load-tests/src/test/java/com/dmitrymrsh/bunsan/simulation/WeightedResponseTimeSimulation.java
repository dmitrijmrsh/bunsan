package com.dmitrymrsh.bunsan.simulation;

/**
 * Симуляция алгоритма Weighted Response Time.
 *
 * <p>Ожидаемое поведение:
 * <ul>
 *   <li>В фазе прогрева: равномерное распределение (round-robin fallback до набора window-size).</li>
 *   <li>В стабильной фазе: трафик смещается к быстрым инстансам (demo-service-1..3).</li>
 *   <li>В фазе деградации: demo-service-4 получает меньше трафика (высокая EMA-latency).</li>
 *   <li>В фазе наблюдения: видно, как инстанс «реабилитируется» при снижении latency.</li>
 * </ul>
 *
 * <p>Запуск:
 * <pre>
 *   mvn gatling:test -pl load-tests \
 *     -Dgatling.simulationClass=com.dmitrymrsh.bunsan.simulation.WeightedResponseTimeSimulation \
 *     -Dgatling.baseUrl=http://localhost:8080
 * </pre>
 *
 * <p>demo-gateway должен быть запущен с {@code BUNSAN_ALGORITHM=weighted-response-time}
 */
public class WeightedResponseTimeSimulation extends BunsanBaseSimulation {

    public WeightedResponseTimeSimulation() {
        runSimulation();
    }

    @Override
    protected String strategyName() {
        return "weighted-response-time";
    }
}
