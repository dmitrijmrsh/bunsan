variable "yc_folder_id" {
  description = "ID каталога Yandex Cloud, в котором развёртывается стенд"
  type        = string
}

variable "yc_zone" {
  description = "Зона доступности Yandex Cloud"
  type        = string
  default     = "ru-central1-a"
}

variable "registry_id" {
  description = "ID реестра Yandex Container Registry, в котором хранятся образы приложений"
  type        = string
}

variable "container_image_tag" {
  description = "Тег образов в Container Registry — обычно git-sha сборки (например, v1.2.3-abc1234)"
  type        = string
}

variable "balancer_strategy" {
  description = "Алгоритм балансировки на gateway"
  type        = string
  default     = "adaptive"
  validation {
    condition = contains(
      ["random", "round-robin", "weighted-response-time", "least-connections", "adaptive"],
      var.balancer_strategy
    )
    error_message = "balancer_strategy должен быть одним из: random, round-robin, weighted-response-time, least-connections, adaptive."
  }
}

variable "demo_service_instances" {
  description = "Число инстансов demo-service"
  type        = number
  default     = 4
}

variable "stress_enabled_count" {
  description = "Сколько инстансов запустить с STRESS_ENABLED=true (CPU-burn, имитация деградации)"
  type        = number
  default     = 1
  validation {
    condition     = var.stress_enabled_count >= 0 && var.stress_enabled_count <= var.demo_service_instances
    error_message = "stress_enabled_count не может превышать demo_service_instances."
  }
}

variable "stress_delay_ms" {
  description = "Искусственная задержка HTTP-ответа (мс) для инстансов с деградацией. Делает замедление видимым на уровне latency — необходимо для WRT и LC."
  type        = number
  default     = 0
}

variable "stress_delay_count" {
  description = "Сколько инстансов запустить с STRESS_DELAY_MS (первые N по индексу)"
  type        = number
  default     = 0
  validation {
    condition     = var.stress_delay_count >= 0 && var.stress_delay_count <= var.demo_service_instances
    error_message = "stress_delay_count не может превышать demo_service_instances."
  }
}

variable "demo_service_vm_resources" {
  description = "Ресурсы ВМ для каждого инстанса demo-service"
  type = object({
    cores         = number
    memory        = number
    core_fraction = number # 20/50/100 — burstable или dedicated
  })
  default = {
    cores         = 2
    memory        = 2
    core_fraction = 100
  }
}

variable "overload_filter_enabled" {
  description = "Включить серверный фильтр Eureka по статусу OUT_OF_SERVICE"
  type        = bool
  default     = true
}

variable "algorithm_tuning" {
  description = "Тонкая настройка алгоритмов (передаётся в application.yml через cloud-init)"
  type = object({
    ema_alpha             = optional(number, 0.3)
    window_size           = optional(number, 5)
    tie_breaking          = optional(string, "random")
    metrics_poll_interval = optional(string, "5s")
    cpu_threshold         = optional(number, 0.80)
    heap_threshold        = optional(number, 0.85)
    adaptive_weights = optional(object({
      cpu     = number
      heap    = number
      threads = number
      latency = number
    }), { cpu = 0.35, heap = 0.25, threads = 0.20, latency = 0.20 })
  })
  default = {}
}

variable "gatling_users" {
  description = "Целевое число одновременных пользователей в Gatling"
  type        = number
  default     = 200
}

variable "gatling_ramp_up_seconds" {
  description = "Длительность фазы прогрева (секунды)"
  type        = number
  default     = 120
}

variable "gatling_steady_seconds" {
  description = "Длительность фазы стабильной нагрузки (секунды)"
  type        = number
  default     = 300
}

variable "ssh_public_key_path" {
  description = "Путь к публичному SSH-ключу для доступа к ВМ"
  type        = string
  default     = "~/.ssh/id_ed25519.pub"
}
