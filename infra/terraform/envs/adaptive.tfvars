# ─── Сценарий: Adaptive (composite score) ────────────────────────────────────
# Комбинирует CPU, heap, threads и latency в единый score.
# Выбирается инстанс с наименьшим score — наиболее незагруженный и быстрый.

yc_folder_id            = "b1g3qloets2970m2kkue"
registry_id             = "crpg108nl0rhspi9csu6"
container_image_tag     = "v0.1.0"

balancer_strategy       = "adaptive"
demo_service_instances  = 4
stress_enabled_count    = 1
overload_filter_enabled = true

gatling_users           = 200
gatling_ramp_up_seconds = 120
gatling_steady_seconds  = 300

algorithm_tuning = {
  ema_alpha             = 0.3
  metrics_poll_interval = "5s"
  cpu_threshold         = 0.80
  heap_threshold        = 0.85
  adaptive_weights = {
    cpu     = 0.35
    heap    = 0.25
    threads = 0.20
    latency = 0.20
  }
}
