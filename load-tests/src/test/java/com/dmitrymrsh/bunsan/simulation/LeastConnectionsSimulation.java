package com.dmitrymrsh.bunsan.simulation;

/**
 * Симуляция алгоритма Least Connections.
 *
 * <p>Ожидаемое поведение:
 * <ul>
 *   <li>В стабильной фазе: равномерное распределение (все инстансы быстрые, соединения ≈ равны).</li>
 *   <li>В фазе деградации: demo-service-4 накапливает in-flight запросы из-за медленных ответов,
 *       балансировщик автоматически перенаправляет трафик к инстансам с меньшим числом соединений.</li>
 *   <li>client-side учёт: счётчики ведутся только в данном gateway-процессе.</li>
 * </ul>
 *
 * <p>Запуск:
 * <pre>
 *   mvn gatling:test -pl load-tests \
 *     -Dgatling.simulationClass=com.dmitrymrsh.bunsan.simulation.LeastConnectionsSimulation \
 *     -Dgatling.baseUrl=http://localhost:8080
 * </pre>
 *
 * <p>demo-gateway должен быть запущен с {@code BUNSAN_ALGORITHM=least-connections}
 */
public class LeastConnectionsSimulation extends BunsanBaseSimulation {

    public LeastConnectionsSimulation() {
        runSimulation();
    }

    @Override
    protected String strategyName() {
        return "least-connections";
    }
}
