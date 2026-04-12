package com.dmitrymrsh.bunsan.simulation;

/**
 * Baseline-симуляция: алгоритм случайного выбора (Random).
 *
 * <p>Запуск:
 * <pre>
 *   mvn gatling:test -pl load-tests \
 *     -Dgatling.simulationClass=com.dmitrymrsh.bunsan.simulation.RandomSimulation \
 *     -Dgatling.baseUrl=http://localhost:8080
 * </pre>
 *
 * <p>Перед запуском убедитесь, что demo-gateway запущен с:
 * {@code BUNSAN_ALGORITHM=random}
 */
public class RandomSimulation extends BunsanBaseSimulation {

    public RandomSimulation() {
        runSimulation();
    }

    @Override
    protected String strategyName() {
        return "random";
    }
}
