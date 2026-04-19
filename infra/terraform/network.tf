resource "yandex_vpc_network" "bunsan" {
  name = "bunsan-net"
}

resource "yandex_vpc_gateway" "bunsan" {
  name = "bunsan-nat-gateway"
  shared_egress_gateway {}
}

resource "yandex_vpc_route_table" "bunsan" {
  name       = "bunsan-route-table"
  network_id = yandex_vpc_network.bunsan.id

  static_route {
    destination_prefix = "0.0.0.0/0"
    gateway_id         = yandex_vpc_gateway.bunsan.id
  }
}

resource "yandex_vpc_subnet" "bunsan" {
  name           = "bunsan-subnet-${var.yc_zone}"
  zone           = var.yc_zone
  network_id     = yandex_vpc_network.bunsan.id
  v4_cidr_blocks = ["10.10.0.0/24"]
  route_table_id = yandex_vpc_route_table.bunsan.id
}

resource "yandex_vpc_security_group" "bunsan" {
  name       = "bunsan-sg"
  network_id = yandex_vpc_network.bunsan.id

  # Внутренний трафик стенда — без ограничений.
  ingress {
    protocol       = "ANY"
    description    = "intra-stand"
    v4_cidr_blocks = ["10.10.0.0/24"]
    from_port      = 0
    to_port        = 65535
  }

  # SSH — доступ для отладки. В продакшене ограничить конкретным IP оператора.
  ingress {
    protocol       = "TCP"
    description    = "ssh"
    v4_cidr_blocks = ["0.0.0.0/0"]
    port           = 22
  }

  # Публичный доступ к Eureka (8761), gateway (8080), Grafana (3000), Prometheus (9090).
  ingress {
    protocol       = "TCP"
    description    = "eureka"
    v4_cidr_blocks = ["0.0.0.0/0"]
    port           = 8761
  }

  ingress {
    protocol       = "TCP"
    description    = "gateway"
    v4_cidr_blocks = ["0.0.0.0/0"]
    port           = 8080
  }

  ingress {
    protocol       = "TCP"
    description    = "grafana"
    v4_cidr_blocks = ["0.0.0.0/0"]
    port           = 3000
  }

  ingress {
    protocol       = "TCP"
    description    = "prometheus"
    v4_cidr_blocks = ["0.0.0.0/0"]
    port           = 9090
  }

  egress {
    protocol       = "ANY"
    description    = "any-out"
    v4_cidr_blocks = ["0.0.0.0/0"]
    from_port      = 0
    to_port        = 65535
  }
}
