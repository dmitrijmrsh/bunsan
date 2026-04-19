output "eureka_url" {
  description = "Адрес Eureka Server (реестр сервисов)"
  value       = "http://${yandex_compute_instance.eureka.network_interface[0].nat_ip_address}:8761"
}

output "gateway_url" {
  description = "Адрес Gateway — точка входа для нагрузочных тестов"
  value       = "http://${yandex_compute_instance.gateway.network_interface[0].nat_ip_address}:8080"
}

output "grafana_url" {
  description = "Адрес Grafana (admin/admin)"
  value       = "http://${yandex_compute_instance.observability.network_interface[0].nat_ip_address}:3000"
}

output "prometheus_url" {
  description = "Адрес Prometheus"
  value       = "http://${yandex_compute_instance.observability.network_interface[0].nat_ip_address}:9090"
}

output "demo_service_ips" {
  description = "Внутренние IP-адреса инстансов demo-service"
  value       = { for k, v in yandex_compute_instance.demo_service : "demo-service-${k}" => v.network_interface[0].ip_address }
}

output "gatling_env" {
  description = "Переменные для запуска Gatling — скопируйте в терминал"
  value       = <<-EOT
    export GATEWAY_URL=http://${yandex_compute_instance.gateway.network_interface[0].nat_ip_address}:8080
    export EUREKA_URL=http://${yandex_compute_instance.eureka.network_interface[0].nat_ip_address}:8761
  EOT
}

output "experiment_summary" {
  description = "Сводка параметров эксперимента — копируется в лог прогона"
  value = {
    strategy        = var.balancer_strategy
    instances       = var.demo_service_instances
    stress_enabled  = var.stress_enabled_count
    overload_filter = var.overload_filter_enabled
    gatling_users   = var.gatling_users
    image_tag       = var.container_image_tag
  }
}
