# syntax=docker/dockerfile:1.7

# ─── Stage 1: build ─────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace

# Copiamos SOLO el pom primero y resolvemos dependencias sin código.
# Docker cachea esta capa: mientras pom.xml no cambie, ni una descarga de
# Maven se repite aunque src/ cambie — que es lo que pasa en 99% de los
# rebuilds durante desarrollo.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Ahora sí copiamos el código y construimos.
COPY src ./src

# skipTests deliberado: los tests de integración usan Testcontainers, que
# a su vez levanta contenedores Docker. Correrlos dentro del build de la
# imagen exigiría Docker-in-Docker (privilegiado, complejo, lento). Los
# tests corren en local (./mvnw clean install) y en CI, no en el build.
RUN mvn -B -q package -DskipTests


# ─── Stage 2: runtime ───────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

# Usuario no-root. Si el proceso se ve comprometido, el atacante no obtiene
# root del contenedor — que hoy, con user-namespace mal configurado, puede
# aterrizar como root del host.
RUN addgroup -S spin && adduser -S spin -G spin

WORKDIR /app

# --chown asegura que el jar llegue con propietario correcto en un solo
# COPY, sin un RUN chown extra que crearía una capa adicional.
COPY --from=build --chown=spin:spin /workspace/target/*.jar app.jar

USER spin

EXPOSE 8080

# -XX:MaxRAMPercentage en vez de -Xmx: la JVM en un contenedor con memory
# limit (Kubernetes, docker --memory, compose deploy.resources.limits) SÍ
# lee el cgroup para decidir cuánto heap usar. Con -Xmx=512m hardcodeado,
# si mañana subes el limit del pod la JVM sigue en 512m. Con
# MaxRAMPercentage=75.0 escala automáticamente al 75% de lo que el cgroup
# le ofrezca — el 25% restante queda para stacks, metaspace y off-heap.
#
# Exec form ["java", ...] en vez de shell form ("java ..."): así el PID 1
# ES la JVM, no un shell intermedio, y los SIGTERM del orquestador llegan
# directo — necesario para el graceful shutdown de Spring Boot.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
