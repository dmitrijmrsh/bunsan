# ─── Сценарий: Random (baseline) ─────────────────────────────────────────────
# Стандартный алгоритм Spring Cloud LoadBalancer — случайный выбор инстанса.
# Служит базовой линией для сравнения кастомных стратегий вместе с Round Robin.

yc_folder_id            = "b1g3qloets2970m2kkue"
registry_id             = "crpg108nl0rhspi9csu6"
container_image_tag     = "v0.1.0"

balancer_strategy       = "random"
demo_service_instances  = 4
stress_enabled_count    = 1
overload_filter_enabled = false  # baseline: фильтр выключен

gatling_users           = 200
gatling_ramp_up_seconds = 120
gatling_steady_seconds  = 300

algorithm_tuning = {
  cpu_threshold  = 0.80
  heap_threshold = 0.85
}
