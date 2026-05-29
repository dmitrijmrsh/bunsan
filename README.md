# bunsan

Spring Boot Starter библиотека, расширяющая стандартные алгоритмы балансировки нагрузки в экосистеме Spring Cloud.

Реализует три кастомных алгоритма балансировки поверх Spring Cloud LoadBalancer, а также механизм фильтрации перегруженных инстансов на стороне Eureka Server.

## Алгоритмы балансировки

| Алгоритм | Описание |
|---|---|
| `weighted-response-time` | Инстансы с меньшим временем ответа получают больше трафика. Вес обновляется через EMA по каждому запросу |
| `least-connections` | Запрос направляется к инстансу с наименьшим числом активных запросов |
| `adaptive` | Комбинирует CPU, heap, активные потоки и latency в единый score; фоново опрашивает `/actuator/lb-health` каждого инстанса |

## Быстрый старт

```bash
# Собрать все модули
mvn clean install

# Поднять локальный стенд (Eureka + Gateway + 4 инстанса demo-service + Prometheus + Grafana)
docker compose -f infra/docker-compose.yml up -d
```

После старта доступно:
- Eureka Dashboard — `http://localhost:8761`
- Gateway — `http://localhost:8080`
- Grafana — `http://localhost:3000`
- Prometheus — `http://localhost:9090`

## Структура проекта

```
bunsan/
├── bunsan-spring-boot-starter/          # Ядро библиотеки — алгоритмы, автоконфигурация, метрики, фильтр Eureka
│   └── src/main/java/com/dmitrymrsh/bunsan/
│       ├── algorithm/                   # Реализации балансировщиков и вспомогательные трекеры
│       │   ├── WeightedResponseTimeLoadBalancer.java
│       │   ├── LeastConnectionsLoadBalancer.java
│       │   ├── AdaptiveLoadBalancer.java
│       │   ├── LatencyTracker.java      # Общий EMA-трекер задержек (используется WRT и Adaptive)
│       │   ├── ConnectionTracker.java   # Счётчики in-flight запросов для LeastConnections
│       │   └── InstanceHealthSnapshot.java  # Record со снапшотом метрик инстанса для Adaptive
│       ├── discovery/                   # Фильтрация перегруженных инстансов через Eureka
│       │   ├── OverloadHealthIndicator.java  # Клиентская часть: проверяет CPU/heap, меняет статус в Eureka
│       │   └── OverloadInstanceFilter.java   # Серверная часть: исключает OUT_OF_SERVICE из реестра
│       ├── metrics/                     # Метрики Micrometer и кастомный Actuator-эндпоинт
│       │   ├── LoadBalancerMetrics.java       # Счётчики запросов, latency, in-flight, веса, scores
│       │   ├── LatencyTrackingGlobalFilter.java  # GlobalFilter для перехвата времени ответа на gateway
│       │   └── LbHealthEndpoint.java          # /actuator/lb-health — сводка метрик для AdaptiveLoadBalancer
│       └── starter/                     # Автоконфигурация Spring Boot
│           ├── BunsanAutoConfiguration.java   # Главный @AutoConfiguration-класс
│           ├── BunsanClientConfiguration.java # @LoadBalancerClientConfiguration — привязка к сервису
│           └── BunsanProperties.java          # @ConfigurationProperties(prefix = "bunsan") — все настройки
│
├── demo-eureka-server/                  # Eureka Server с включённым OverloadInstanceFilter
├── demo-service/                        # Простой REST-сервис; запускается в нескольких инстансах
│                                        # При STRESS_ENABLED=true имитирует CPU-деградацию
├── demo-gateway/                        # Spring Cloud Gateway, использующий bunsan-spring-boot-starter
│
├── load-tests/                          # Нагрузочные тесты на Gatling (Java DSL)
│   ├── src/test/java/.../simulation/
│   │   ├── BunsanBaseSimulation.java    # Базовый сценарий: прогрев → стабильная нагрузка → деградация
│   │   ├── RandomSimulation.java
│   │   ├── RoundRobinSimulation.java
│   │   ├── WeightedResponseTimeSimulation.java
│   │   ├── LeastConnectionsSimulation.java
│   │   └── AdaptiveSimulation.java
│   └── results/                         # CSV и HTML-отчёты Gatling после каждого прогона
│
├── analysis/                            # Python-скрипты для построения графиков по результатам Gatling
│   ├── parse_gatling_logs.py            # Парсинг simulation.log → combined_results.csv
│   ├── boxplot_latency.py              # Box plot времени ответа по стратегиям
│   ├── cdf_response_time.py            # CDF времени ответа
│   ├── throughput_over_time.py         # RPS по времени (виден эффект деградации)
│   ├── error_rate.py                   # Bar chart процента ошибок по стратегиям
│   ├── request_distribution.py         # Распределение запросов по инстансам
│   ├── percentiles_table.py            # Сводная таблица p50/p95/p99/max/error rate
│   ├── run_all_experiments.sh          # Оркестратор: запускает terraform + gatling для всех стратегий
│   └── requirements.txt                # matplotlib, seaborn, pandas
│
└── infra/                               # Инфраструктура для локального и облачного стендов
    ├── docker-compose.yml               # Локальный стенд: все сервисы + Prometheus + Grafana
    ├── prometheus.yml                   # Конфигурация scrape-таргетов Prometheus
    ├── grafana/
    │   ├── dashboards/                  # JSON-дашборды Grafana (загружаются автоматически)
    │   │   ├── load-distribution.json   # Распределение запросов по инстансам
    │   │   ├── latency-by-instance.json # p50/p95/p99 latency каждого инстанса
    │   │   ├── instance-health.json     # CPU, heap, потоки по инстансам
    │   │   └── balancer-internals.json  # Веса, scores, in-flight счётчики балансировщика
    │   └── provisioning/               # Конфигурация автопровижининга Grafana
    │       ├── dashboards/dashboards.yml
    │       └── datasources/prometheus.yml
    └── terraform/                       # IaC для облачного стенда в Yandex Cloud
        ├── main.tf                      # Провайдер yandex, бэкенд состояния в Object Storage
        ├── variables.tf                 # Параметры эксперимента (стратегия, число инстансов, нагрузка)
        ├── network.tf                   # VPC, подсеть, security group
        ├── compute.tf                   # ВМ: Eureka, Gateway, demo-service × N, Prometheus+Grafana
        ├── outputs.tf                   # URL Eureka, Gateway, Grafana, Prometheus после apply
        ├── cloud-init/                  # cloud-init шаблоны для каждой роли ВМ
        │   ├── eureka.yaml.tftpl
        │   ├── gateway.yaml.tftpl
        │   ├── demo-service.yaml.tftpl
        │   └── observability.yaml.tftpl
        ├── envs/                        # Один .tfvars-файл на сценарий эксперимента
        │   ├── random.tfvars
        │   ├── round-robin.tfvars
        │   ├── weighted-response-time.tfvars
        │   ├── least-connections.tfvars
        │   └── adaptive.tfvars
        └── README.md                    # Инструкция по развёртыванию облачного стенда
```

## Конфигурация

Все свойства стартера задаются под префиксом `bunsan.*` в `application.yml`:

```yaml
bunsan:
  strategy: adaptive   # round-robin | weighted-response-time | least-connections | adaptive

  weighted-response-time:
    ema-alpha: 0.3       # чувствительность EMA к последним замерам (0..1)
    window-size: 10      # минимум замеров перед переключением с round-robin

  least-connections:
    tie-breaking: random # стратегия выбора при равных счётчиках

  adaptive:
    metrics-poll-interval: 5s
    weights:
      cpu: 0.3
      heap: 0.2
      threads: 0.2
      latency: 0.3

  discovery:
    overload-filter:
      enabled: true
      cpu-threshold: 0.85
      heap-threshold: 0.85
```

## Облачный стенд (Yandex Cloud)

Финальные нагрузочные эксперименты проводятся в Yandex Cloud через Terraform — это обеспечивает изоляцию ресурсов и воспроизводимость стенда между прогонами.

```bash
cd infra/terraform
terraform init
terraform apply -var-file=envs/adaptive.tfvars
# ... запустить нагрузочные тесты ...
terraform destroy -var-file=envs/adaptive.tfvars
```

Подробная инструкция по развёртыванию — в [`infra/terraform/README.md`](infra/terraform/README.md).

## Команды сборки

```bash
mvn clean install                          # собрать все модули
mvn clean install -DskipTests             # без тестов
mvn test -pl bunsan-spring-boot-starter   # юнит-тесты стартера
mvn verify -pl load-tests                  # нагрузочные тесты Gatling
docker compose -f infra/docker-compose.yml down  # остановить локальный стенд
```
