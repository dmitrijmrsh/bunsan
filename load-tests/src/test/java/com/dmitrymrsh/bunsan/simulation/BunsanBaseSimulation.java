package com.dmitrymrsh.bunsan.simulation;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Базовый класс Gatling-симуляций для сравнения алгоритмов балансировки.
 *
 * <p>Сценарий:
 * <ul>
 *   <li>Phase 1 — Warmup        : 2 мин, нарастание 1 → TARGET_RPS</li>
 *   <li>Phase 2 — Stable Load   : 5 мин, постоянная нагрузка TARGET_RPS</li>
 *   <li>Phase 3 — Degradation   : 1 мин, параллельная CPU-нагрузка + штатный трафик</li>
 *   <li>Phase 4 — Observation   : 5 мин, наблюдение за восстановлением</li>
 * </ul>
 *
 * <p>Конкретные симуляции задают {@link #strategyName()} и при необходимости
 * переопределяют {@code targetRps} или {@code baseUrl} через системные свойства:
 * <pre>
 *   -Dgatling.baseUrl=http://localhost:8080
 *   -Dgatling.targetRps=20
 * </pre>
 */
public abstract class BunsanBaseSimulation extends Simulation {

    // ─── Параметры (переопределяются через -D) ────────────────────────────────
    protected final String  baseUrl   = System.getProperty("gatling.baseUrl", "http://localhost:8080");
    protected final double  targetRps = Double.parseDouble(System.getProperty("gatling.targetRps", "2500"));
    protected final double  stressRps = Double.parseDouble(System.getProperty("gatling.stressRps", "500"));

    /** Название стратегии — задаётся подклассом. Используется в логах и названиях запросов. */
    protected abstract String strategyName();

    // ─── HTTP protocol ────────────────────────────────────────────────────────
    protected HttpProtocolBuilder httpProtocol() {
        return http
            .baseUrl(baseUrl)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            // Передаём название стратегии в заголовке для трейсинга в логах demo-service
            .header("X-LB-Strategy", strategyName())
            .shareConnections();
    }

    // ─── Запросы ──────────────────────────────────────────────────────────────

    /** Главный запрос: быстрый ответ /api/hello */
    protected ChainBuilder helloRequest() {
        return exec(
            http("hello [" + strategyName() + "]")
                .get("/api/hello")
                .check(status().is(200))
                .check(jsonPath("$.instanceId").saveAs("instanceId"))
        ).pause(Duration.ofMillis(50));
    }

    /** Сценарий с задержкой — симулирует смешанную нагрузку */
    protected ChainBuilder delayRequest() {
        return exec(
            http("delay-50ms [" + strategyName() + "]")
                .get("/api/delay/50")
                .check(status().is(200))
        ).pause(Duration.ofMillis(100));
    }

    /** Смешанный сценарий: 80% быстрых + 20% медленных запросов */
    protected ScenarioBuilder mixedScenario() {
        return scenario("Mixed Load [" + strategyName() + "]")
            .randomSwitch().on(
                percent(80.0).then(helloRequest()),
                percent(20.0).then(delayRequest())
            );
    }

    /** Сценарий инъекции деградации.
     *  Посылает CPU-нагружающие запросы в фазе деградации. */
    protected ScenarioBuilder degradationScenario() {
        return scenario("Degradation Injection [" + strategyName() + "]")
            .exec(
                http("cpu-burn [" + strategyName() + "]")
                    .get("/api/cpu/500000")
                    .check(status().is(200))
            )
            .pause(Duration.ofMillis(200));
    }

    // ─── Профиль нагрузки ─────────────────────────────────────────────────────

    /**
     * Запускает полный 13-минутный сценарий.
     * Подклассы вызывают этот метод в конструкторе.
     */
    protected SetUp runSimulation() {
        return setUp(
            // Phase 1+2+3+4: основной трафик непрерывно
            mixedScenario().injectOpen(
                rampUsersPerSec(1).to(targetRps).during(Duration.ofMinutes(2)),   // прогрев
                constantUsersPerSec(targetRps).during(Duration.ofMinutes(5)),      // стабильная нагрузка
                constantUsersPerSec(targetRps).during(Duration.ofMinutes(1)),      // фаза деградации
                constantUsersPerSec(targetRps).during(Duration.ofMinutes(5))       // наблюдение за восстановлением
            ),
            // Phase 3: CPU-нагрузка запускается на 7-й минуте (2+5) и длится 1 минуту
            degradationScenario().injectOpen(
                nothingFor(Duration.ofMinutes(7)),
                constantUsersPerSec(stressRps).during(Duration.ofMinutes(1))
            )
        ).protocols(httpProtocol());
    }
}
