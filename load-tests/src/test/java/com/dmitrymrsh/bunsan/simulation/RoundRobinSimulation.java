package com.dmitrymrsh.bunsan.simulation;

/**
 * Baseline-симуляция: стандартный алгоритм Round-Robin из Spring Cloud LoadBalancer.
 *
 * <p>Запуск:
 * <pre>
 *   mvn gatling:test -pl load-tests \
 *     -Dgatling.simulationClass=com.dmitrymrsh.bunsan.simulation.RoundRobinSimulation \
 *     -Dgatling.baseUrl=http://localhost:8080
 * </pre>
 *
 * <p>Перед запуском убедитесь, что demo-gateway запущен с:
 * {@code BUNSAN_ALGORITHM=round-robin}
 */
public class RoundRobinSimulation extends BunsanBaseSimulation {

    public RoundRobinSimulation() {
        runSimulation();
    }

    @Override
    protected String strategyName() {
        return "round-robin";
    }
}
