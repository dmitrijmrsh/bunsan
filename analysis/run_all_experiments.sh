#!/usr/bin/env bash
# run_all_experiments.sh — оркестрация серии нагрузочных экспериментов в Yandex Cloud.
#
# Для каждого .tfvars-файла в infra/terraform/envs/:
#   1. terraform apply   — поднять стенд
#   2. Ожидание готовности Eureka
#   3. mvn verify        — запустить Gatling
#   4. Сохранить результаты в load-tests/results/cloud/<strategy>/
#   5. terraform destroy — снести стенд
#
# Использование:
#   export IMAGE_TAG=v1.2.3-abc1234
#   cd infra/terraform
#   bash ../../analysis/run_all_experiments.sh
#
# Переменные окружения (обязательные):
#   IMAGE_TAG                — тег образов в Container Registry
#   YC_TOKEN                 — IAM-токен Yandex Cloud (или YC_SERVICE_ACCOUNT_KEY_FILE)
#   AWS_ACCESS_KEY_ID        — ключ для S3-бэкенда Terraform
#   AWS_SECRET_ACCESS_KEY    — секрет для S3-бэкенда Terraform

set -euo pipefail

# ─── Проверка окружения ───────────────────────────────────────────────────────

: "${IMAGE_TAG:?Переменная IMAGE_TAG не задана. Пример: export IMAGE_TAG=v1.0.0-abc1234}"
: "${AWS_ACCESS_KEY_ID:?Нужен AWS_ACCESS_KEY_ID для S3-бэкенда Terraform}"
: "${AWS_SECRET_ACCESS_KEY:?Нужен AWS_SECRET_ACCESS_KEY для S3-бэкенда Terraform}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TF_DIR="$REPO_ROOT/infra/terraform"
RESULTS_DIR="$REPO_ROOT/load-tests/results/cloud"
LOAD_TESTS_DIR="$REPO_ROOT/load-tests"

EUREKA_READY_TIMEOUT=300   # секунд на ожидание готовности Eureka
EUREKA_POLL_INTERVAL=10    # интервал опроса (секунды)

# ─── Вспомогательные функции ─────────────────────────────────────────────────

log() { echo "[$(date '+%H:%M:%S')] $*"; }

strategy_class_name() {
  case "$1" in
    random)                 echo "RandomSimulation" ;;
    round-robin)            echo "RoundRobinSimulation" ;;
    weighted-response-time) echo "WeightedResponseTimeSimulation" ;;
    least-connections)      echo "LeastConnectionsSimulation" ;;
    adaptive)               echo "AdaptiveSimulation" ;;
    *) log "ОШИБКА: неизвестная стратегия '$1'"; exit 1 ;;
  esac
}

wait_for_eureka() {
  local url="$1"
  local deadline=$(( $(date +%s) + EUREKA_READY_TIMEOUT ))
  log "Ожидание готовности Eureka: $url"
  while [[ $(date +%s) -lt $deadline ]]; do
    if curl -sf "$url/eureka/apps" | grep -q '"status":"UP"'; then
      log "Eureka готова."
      return 0
    fi
    sleep "$EUREKA_POLL_INTERVAL"
    log "  ...ещё ждём"
  done
  log "ОШИБКА: Eureka не стала готовой за ${EUREKA_READY_TIMEOUT}s"
  return 1
}

tf_output() {
  terraform -chdir="$TF_DIR" output -raw "$1" 2>/dev/null
}

# ─── Основной цикл ────────────────────────────────────────────────────────────

mkdir -p "$RESULTS_DIR"

for tfvars_file in "$TF_DIR/envs/"*.tfvars; do
  strategy=$(basename "$tfvars_file" .tfvars)
  log "═══════════════════════════════════════════════"
  log "Стратегия: $strategy"
  log "═══════════════════════════════════════════════"

  # 1. Поднять стенд
  log "terraform apply..."
  terraform -chdir="$TF_DIR" apply \
    -var-file="envs/$strategy.tfvars" \
    -var="container_image_tag=$IMAGE_TAG" \
    -auto-approve

  # Небольшая пауза после провижининга — дать ВМ время стартовать контейнеры.
  sleep 30

  # 2. Дождаться готовности Eureka
  eureka_url=$(tf_output eureka_url)
  gateway_url=$(tf_output gateway_url)
  log "Eureka URL: $eureka_url"
  log "Gateway URL: $gateway_url"

  if ! wait_for_eureka "$eureka_url"; then
    log "ПРОПУСК: Eureka не поднялась для стратегии '$strategy'. Начинаем destroy."
    terraform -chdir="$TF_DIR" destroy \
      -var-file="envs/$strategy.tfvars" \
      -var="container_image_tag=$IMAGE_TAG" \
      -auto-approve
    continue
  fi

  # Дополнительная пауза, чтобы все demo-service инстансы зарегистрировались в Eureka.
  log "Ожидание регистрации всех инстансов (30s)..."
  sleep 30

  # 3. Запустить Gatling
  log "Запуск Gatling (gateway: $gateway_url)..."
  mvn verify -pl load-tests \
    -f "$REPO_ROOT/pom.xml" \
    -Dgateway.url="$gateway_url" \
    -Dgatling.simulationClass="com.dmitrymrsh.bunsan.simulation.$(strategy_class_name "$strategy")" \
    -q || log "ВНИМАНИЕ: Gatling завершился с ненулевым кодом — проверьте результаты."

  # 4. Сохранить результаты
  strategy_results_dir="$RESULTS_DIR/$strategy"
  mkdir -p "$strategy_results_dir"
  latest_run=$(ls -td "$LOAD_TESTS_DIR/results/"*/ 2>/dev/null | head -1)
  if [[ -n "$latest_run" ]]; then
    cp -r "$latest_run" "$strategy_results_dir/"
    log "Результаты сохранены в: $strategy_results_dir"
  else
    log "ВНИМАНИЕ: результаты Gatling не найдены в $LOAD_TESTS_DIR/results/"
  fi

  # 5. Снести стенд
  log "terraform destroy..."
  terraform -chdir="$TF_DIR" destroy \
    -var-file="envs/$strategy.tfvars" \
    -var="container_image_tag=$IMAGE_TAG" \
    -auto-approve

  log "Стратегия '$strategy' завершена."
  echo
done

log "Все эксперименты выполнены. Результаты: $RESULTS_DIR"
log "Следующий шаг: python3 analysis/parse_gatling_logs.py && python3 analysis/boxplot_latency.py"
