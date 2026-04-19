terraform {
  required_version = ">= 1.6.0"

  required_providers {
    yandex = {
      source  = "yandex-cloud/yandex"
      version = "~> 0.190"
    }
  }

  # Удалённое хранение state в Yandex Object Storage (S3-совместимое API).
  # Бакет и сервис-аккаунт с ролью storage.editor создаются вручную ОДИН РАЗ
  # перед первым `terraform init` — bootstrap-шаги описаны в README.md.
  backend "s3" {
    endpoints = {
      s3 = "https://storage.yandexcloud.net"
    }
    bucket                      = "bunsan-tfstate"
    region                      = "ru-central1"
    key                         = "bunsan/terraform.tfstate"
    skip_region_validation      = true
    skip_credentials_validation = true
    skip_requesting_account_id  = true
    skip_s3_checksum            = true
  }
}

provider "yandex" {
  zone      = var.yc_zone
  folder_id = var.yc_folder_id
  # token / service_account_key_file берутся из переменных окружения
  # YC_TOKEN или YC_SERVICE_ACCOUNT_KEY_FILE — в коде не пишутся.
}
