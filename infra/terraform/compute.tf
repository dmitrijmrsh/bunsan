# Базовый образ Container Optimized Image — Docker предустановлен,
# аутентификация в Yandex Container Registry выполняется автоматически
# через сервис-аккаунт ВМ (роль container-registry.images.puller).
data "yandex_compute_image" "coi" {
  family = "container-optimized-image"
}

# ─── Сервис-аккаунт для скачивания образов из Container Registry ─────────────

resource "yandex_iam_service_account" "bunsan_registry" {
  name        = "bunsan-registry-puller"
  description = "Скачивание образов приложений из Yandex Container Registry"
  folder_id   = var.yc_folder_id
}

resource "yandex_resourcemanager_folder_iam_member" "registry_puller" {
  folder_id = var.yc_folder_id
  role      = "container-registry.images.puller"
  member    = "serviceAccount:${yandex_iam_service_account.bunsan_registry.id}"
}

# ─── Локальные переменные ────────────────────────────────────────────────────

locals {
  ssh_keys = "ubuntu:${file(pathexpand(var.ssh_public_key_path))}"

  image_prefix = "cr.yandex/${var.registry_id}"

  # Список инстансов demo-service: первые stress_enabled_count запускаются
  # с включённой нагрузочной имитацией.
  demo_instances = [
    for i in range(var.demo_service_instances) : {
      index           = i + 1
      stress_enabled  = i < var.stress_enabled_count
      stress_delay_ms = i < var.stress_delay_count ? var.stress_delay_ms : 0
    }
  ]
}

# ─── Eureka Server ───────────────────────────────────────────────────────────

resource "yandex_compute_instance" "eureka" {
  name               = "bunsan-eureka"
  platform_id        = "standard-v3"
  zone               = var.yc_zone
  service_account_id = yandex_iam_service_account.bunsan_registry.id

  # Ждём пока IAM-права распространятся перед запуском ВМ,
  # иначе cloud-init не сможет аутентифицироваться в Container Registry.
  depends_on = [yandex_resourcemanager_folder_iam_member.registry_puller]

  resources {
    cores         = 2
    memory        = 2
    core_fraction = 100
  }

  boot_disk {
    initialize_params {
      image_id = data.yandex_compute_image.coi.id
      size     = 20
      type     = "network-ssd"
    }
  }

  network_interface {
    subnet_id          = yandex_vpc_subnet.bunsan.id
    nat                = true
    security_group_ids = [yandex_vpc_security_group.bunsan.id]
  }

  metadata = {
    ssh-keys  = local.ssh_keys
    user-data = templatefile("${path.module}/cloud-init/eureka.yaml.tftpl", {
      image           = "${local.image_prefix}/bunsan-eureka-server:${var.container_image_tag}"
      filter_enabled  = var.overload_filter_enabled
      cpu_threshold   = var.algorithm_tuning.cpu_threshold
      heap_threshold  = var.algorithm_tuning.heap_threshold
    })
  }
}

# ─── Gateway ─────────────────────────────────────────────────────────────────

resource "yandex_compute_instance" "gateway" {
  name               = "bunsan-gateway"
  platform_id        = "standard-v3"
  zone               = var.yc_zone
  service_account_id = yandex_iam_service_account.bunsan_registry.id
  depends_on         = [yandex_resourcemanager_folder_iam_member.registry_puller]

  resources {
    cores         = 2
    memory        = 2
    core_fraction = 100
  }

  boot_disk {
    initialize_params {
      image_id = data.yandex_compute_image.coi.id
      size     = 20
      type     = "network-ssd"
    }
  }

  network_interface {
    subnet_id          = yandex_vpc_subnet.bunsan.id
    nat                = true
    security_group_ids = [yandex_vpc_security_group.bunsan.id]
  }

  metadata = {
    ssh-keys  = local.ssh_keys
    user-data = templatefile("${path.module}/cloud-init/gateway.yaml.tftpl", {
      image             = "${local.image_prefix}/bunsan-gateway:${var.container_image_tag}"
      eureka_host       = yandex_compute_instance.eureka.network_interface[0].ip_address
      balancer_strategy = var.balancer_strategy
      tuning            = var.algorithm_tuning
      filter_enabled    = var.overload_filter_enabled
    })
  }

  # Пересоздаётся при изменении стратегии балансировки или тюнинга,
  # так как cloud-init применяется только при первом запуске ВМ.
  lifecycle {
    replace_triggered_by = [terraform_data.gateway_config_hash]
  }
}

# Хэш конфигурации gateway — триггер пересоздания ВМ при изменении алгоритма.
resource "terraform_data" "gateway_config_hash" {
  input = sha256(jsonencode({
    strategy = var.balancer_strategy
    tuning   = var.algorithm_tuning
  }))
}

# ─── demo-service (N инстансов) ──────────────────────────────────────────────

resource "yandex_compute_instance" "demo_service" {
  for_each = { for inst in local.demo_instances : inst.index => inst }

  name               = "bunsan-demo-${each.value.index}"
  platform_id        = "standard-v3"
  zone               = var.yc_zone
  service_account_id = yandex_iam_service_account.bunsan_registry.id
  depends_on         = [
    yandex_resourcemanager_folder_iam_member.registry_puller,
    yandex_compute_instance.eureka,
  ]

  resources {
    cores         = var.demo_service_vm_resources.cores
    memory        = var.demo_service_vm_resources.memory
    core_fraction = var.demo_service_vm_resources.core_fraction
  }

  boot_disk {
    initialize_params {
      image_id = data.yandex_compute_image.coi.id
      size     = 20
      type     = "network-ssd"
    }
  }

  network_interface {
    subnet_id          = yandex_vpc_subnet.bunsan.id
    nat                = false
    security_group_ids = [yandex_vpc_security_group.bunsan.id]
  }

  metadata = {
    ssh-keys  = local.ssh_keys
    user-data = templatefile("${path.module}/cloud-init/demo-service.yaml.tftpl", {
      image           = "${local.image_prefix}/bunsan-demo-service:${var.container_image_tag}"
      eureka_host     = yandex_compute_instance.eureka.network_interface[0].ip_address
      instance_index  = each.value.index
      stress_enabled  = each.value.stress_enabled
      stress_delay_ms = each.value.stress_delay_ms
      cpu_threshold   = var.algorithm_tuning.cpu_threshold
      heap_threshold  = var.algorithm_tuning.heap_threshold
    })
  }
}

# ─── Observability (Prometheus + Grafana на одной ВМ) ────────────────────────

resource "yandex_compute_instance" "observability" {
  name               = "bunsan-observability"
  platform_id        = "standard-v3"
  zone               = var.yc_zone
  service_account_id = yandex_iam_service_account.bunsan_registry.id
  depends_on         = [yandex_resourcemanager_folder_iam_member.registry_puller]

  resources {
    cores         = 2
    memory        = 4
    core_fraction = 100
  }

  boot_disk {
    initialize_params {
      image_id = data.yandex_compute_image.coi.id
      size     = 30
      type     = "network-ssd"
    }
  }

  network_interface {
    subnet_id          = yandex_vpc_subnet.bunsan.id
    nat                = true
    security_group_ids = [yandex_vpc_security_group.bunsan.id]
  }

  metadata = {
    ssh-keys           = local.ssh_keys
    enable-oslogin     = "false"
    serial-port-enable = "1"
    user-data = templatefile("${path.module}/cloud-init/observability.yaml.tftpl", {
      eureka_host        = yandex_compute_instance.eureka.network_interface[0].ip_address
      gateway_host       = yandex_compute_instance.gateway.network_interface[0].ip_address
      demo_service_hosts = [for inst in yandex_compute_instance.demo_service : inst.network_interface[0].ip_address]

      # Дашборды Grafana встраиваются как base64 — они небольшие (~20 KB суммарно)
      # и не требуют дополнительного Object Storage для провижининга.
      load_distribution_b64   = filebase64("${path.module}/../grafana/dashboards/load-distribution.json")
      latency_by_instance_b64 = filebase64("${path.module}/../grafana/dashboards/latency-by-instance.json")
      instance_health_b64     = filebase64("${path.module}/../grafana/dashboards/instance-health.json")
      balancer_internals_b64  = filebase64("${path.module}/../grafana/dashboards/balancer-internals.json")
    })
  }
}
