# ─── Сценарий: Weighted Response Time ────────────────────────────────────────
# Инстансы с меньшим временем ответа получают больше трафика.
# EMA-коэффициент 0.3 обеспечивает плавное реагирование на изменения latency.

yc_folder_id            = "b1g3qloets2970m2kkue"
registry_id             = "crpg108nl0rhspi9csu6"
container_image_tag     = "v0.1.0"

balancer_strategy       = "weighted-response-time"
demo_service_instances  = 4
stress_enabled_count    = 0
stress_delay_count      = 1
stress_delay_ms         = 100
overload_filter_enabled = false

gatling_users           = 200
gatling_ramp_up_seconds = 120
gatling_steady_seconds  = 300

algorithm_tuning = {
  ema_alpha      = 0.3
  window_size    = 5
  cpu_threshold  = 0.80
  heap_threshold = 0.85
}
