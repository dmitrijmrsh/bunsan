# Terraform — облачный стенд Bunsan (Yandex Cloud)

Декларативное описание стенда для нагрузочных экспериментов.
Один сценарий = один `.tfvars`-файл в `envs/`.

## Быстрый старт

```bash
cd infra/terraform

# Применить стенд для конкретного алгоритма
terraform apply -var-file=envs/adaptive.tfvars -var="container_image_tag=<TAG>"

# Посмотреть адреса сервисов
terraform output

# Снести стенд после эксперимента
terraform destroy -var-file=envs/adaptive.tfvars -var="container_image_tag=<TAG>"
```

## Bootstrap (выполнить один раз перед первым `terraform init`)

### 1. Сервис-аккаунт для Terraform + S3-бэкенд

```bash
# Создать сервис-аккаунт
yc iam service-account create --name bunsan-tf

# Выдать роль editor на каталог
yc resource-manager folder add-access-binding <FOLDER_ID> \
  --role editor \
  --service-account-name bunsan-tf

# Создать статический ключ для S3 API
yc iam access-key create --service-account-name bunsan-tf
# → сохранить key_id и secret
```

### 2. Бакет Object Storage для хранения state

```bash
# Создать бакет
yc storage bucket create \
  --name bunsan-tfstate \
  --default-storage-class standard

# Включить версионирование (защита от случайного удаления state)
yc storage bucket update --name bunsan-tfstate --versioning enabled

# Выдать сервис-аккаунту права на бакет
yc storage bucket update --name bunsan-tfstate \
  --grants grant-type=service-account,service-account-id=<SA_ID>,permission=full-control
```

### 3. Container Registry

```bash
yc container registry create --name bunsan
# → записать ID реестра (cr.yandex/<ID>)
```

### 4. Зеркало провайдеров Terraform

Terraform Registry недоступен из России напрямую — используем зеркало Yandex Cloud.
Создать/дополнить `~/.terraformrc`:

```hcl
provider_installation {
  network_mirror {
    url     = "https://terraform-mirror.yandexcloud.net/"
    include = ["registry.terraform.io/*/*"]
  }
  direct {
    exclude = ["registry.terraform.io/*/*"]
  }
}
```

### 5. Переменные окружения

```bash
# S3-бэкенд
export AWS_ACCESS_KEY_ID="<key_id из шага 1>"
export AWS_SECRET_ACCESS_KEY="<secret из шага 1>"

# Yandex Cloud (выбрать один вариант)
export YC_TOKEN="$(yc iam create-token)"           # IAM-токен (истекает через 1 час)
# или
export YC_SERVICE_ACCOUNT_KEY_FILE="/path/to/key.json"  # ключ сервис-аккаунта (не истекает)
```

### 6. Инициализация Terraform

```bash
cd infra/terraform
terraform init
```

## Сборка и публикация образов

```bash
cd <root>

# Собрать образы
docker build -f demo-eureka-server/Dockerfile -t cr.yandex/<REGISTRY_ID>/bunsan-eureka-server:<TAG> .
docker build -f demo-service/Dockerfile       -t cr.yandex/<REGISTRY_ID>/bunsan-demo-service:<TAG> .
docker build -f demo-gateway/Dockerfile       -t cr.yandex/<REGISTRY_ID>/bunsan-gateway:<TAG> .

# Аутентификация в Container Registry
docker login \
  --username iam \
  --password "$(yc iam create-token)" \
  cr.yandex

# Пуш образов
docker push cr.yandex/<REGISTRY_ID>/bunsan-eureka-server:<TAG>
docker push cr.yandex/<REGISTRY_ID>/bunsan-demo-service:<TAG>
docker push cr.yandex/<REGISTRY_ID>/bunsan-gateway:<TAG>
```

## Настройка .tfvars

Отредактировать поля в нужном `envs/*.tfvars` перед запуском:

| Поле | Где взять |
|---|---|
| `yc_folder_id` | `yc resource-manager folder list` |
| `registry_id` | `yc container registry list` |
| `container_image_tag` | тег, использованный при сборке образов |

Секреты (токены, ключи) **не пишутся** в `.tfvars` — только через переменные окружения.

## Сценарии экспериментов

| Файл | Алгоритм | Фильтр Eureka |
|---|---|---|
| `envs/round-robin.tfvars` | Round Robin (baseline) | выключен |
| `envs/weighted-response-time.tfvars` | Weighted Response Time | включён |
| `envs/least-connections.tfvars` | Least Connections | включён |
| `envs/adaptive.tfvars` | Adaptive | включён |

## Запуск нагрузочного эксперимента (вручную)

```bash
# 1. Поднять стенд
terraform apply -var-file=envs/adaptive.tfvars -var="container_image_tag=<TAG>" -auto-approve

# 2. Проверить готовность Eureka (подождать ~1-2 минуты после apply)
curl $(terraform output -raw eureka_url)/eureka/apps | grep -c '"status":"UP"'
# Должно вернуть 4 (количество инстансов demo-service)

# 3. Запустить Gatling
export GATEWAY_URL=$(terraform output -raw gateway_url)
cd ../../
mvn verify -pl load-tests -Dgateway.url=$GATEWAY_URL

# 4. Снять метрики Prometheus (опционально)
PROM_URL=$(cd infra/terraform && terraform output -raw prometheus_url)
curl "$PROM_URL/api/v1/query_range?query=bunsan_requests_total&start=$(date -d '30 minutes ago' +%s)&end=$(date +%s)&step=15"

# 5. Снести стенд
cd infra/terraform
terraform destroy -var-file=envs/adaptive.tfvars -var="container_image_tag=<TAG>" -auto-approve
```

## Автоматический прогон всех сценариев

```bash
cd infra/terraform
export IMAGE_TAG=<TAG>
bash ../../analysis/run_all_experiments.sh
```

Скрипт последовательно поднимает стенд для каждого алгоритма, запускает Gatling и сохраняет результаты в `load-tests/results/cloud/<strategy>/`.

## Структура файлов

```
infra/terraform/
├── main.tf          — провайдер + S3-бэкенд
├── variables.tf     — все параметры эксперимента
├── network.tf       — VPC, подсеть, security group
├── compute.tf       — ВМ: Eureka, gateway, demo-service×N, observability
├── outputs.tf       — адреса сервисов, сводка эксперимента
├── cloud-init/
│   ├── eureka.yaml.tftpl        — конфиг Eureka Server
│   ├── gateway.yaml.tftpl       — конфиг Gateway с выбором алгоритма
│   ├── demo-service.yaml.tftpl  — конфиг инстанса demo-service
│   └── observability.yaml.tftpl — Prometheus + Grafana (docker compose)
└── envs/
    ├── round-robin.tfvars
    ├── weighted-response-time.tfvars
    ├── least-connections.tfvars
    └── adaptive.tfvars
```

**Важно:** всегда выполнять `terraform destroy` по завершении — простаивающие ВМ продолжают тарифицироваться.
