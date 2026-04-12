FROM eclipse-temurin:25-jdk AS builder
RUN apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/*
WORKDIR /build

# Копируем parent pom и pom стартера для кэширования зависимостей
COPY pom.xml .
COPY bunsan-spring-boot-starter/pom.xml bunsan-spring-boot-starter/
COPY demo-eureka-server/pom.xml demo-eureka-server/
COPY demo-service/pom.xml demo-service/
COPY demo-gateway/pom.xml demo-gateway/
COPY load-tests/pom.xml load-tests/

# Скачиваем зависимости (кэшируется до изменения pom)
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -pl demo-eureka-server -am -q

# Копируем исходники и собираем
COPY bunsan-spring-boot-starter/src bunsan-spring-boot-starter/src
COPY demo-eureka-server/src demo-eureka-server/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -pl demo-eureka-server -am -DskipTests -q

# ─── Runtime image ────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser

COPY --from=builder /build/demo-eureka-server/target/demo-eureka-server-*.jar app.jar

RUN chown appuser:appgroup app.jar
USER appuser

EXPOSE 8761

ENTRYPOINT ["java", "-jar", "app.jar"]
