FROM eclipse-temurin:25-jdk AS builder
RUN apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/*
WORKDIR /build

COPY pom.xml .
COPY bunsan-spring-boot-starter/pom.xml bunsan-spring-boot-starter/
COPY demo-gateway/pom.xml demo-gateway/
COPY demo-eureka-server/pom.xml demo-eureka-server/
COPY demo-service/pom.xml demo-service/
COPY load-tests/pom.xml load-tests/

RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -pl demo-gateway -am -q

COPY bunsan-spring-boot-starter/src bunsan-spring-boot-starter/src
COPY demo-gateway/src demo-gateway/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -pl demo-gateway -am -DskipTests -q

FROM eclipse-temurin:25-jre
WORKDIR /app

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser

COPY --from=builder /build/demo-gateway/target/demo-gateway-*.jar app.jar

RUN chown appuser:appgroup app.jar
USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
