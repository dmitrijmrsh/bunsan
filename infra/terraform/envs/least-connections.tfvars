# ─── Сценарий: Least Connections ─────────────────────────────────────────────
# Запрос направляется к инстансу с наименьшим числом активных соединений.
# При равенстве счётчиков выбирается случайный инстанс.

yc_folder_id            = "b1g3qloets2970m2kkue"
registry_id             = "crpg108nl0rhspi9csu6"
container_image_tag     = "v0.1.0"

balancer_strategy       = "least-connections"
demo_service_instances  = 4
stress_enabled_count    = 0
stress_delay_count      = 1
stress_delay_ms         = 100
overload_filter_enabled = false

gatling_users           = 200
gatling_ramp_up_seconds = 120
gatling_steady_seconds  = 300

algorithm_tuning = {
  tie_breaking   = "random"
  cpu_threshold  = 0.80
  heap_threshold = 0.85
}
