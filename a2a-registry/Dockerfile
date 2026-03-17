# ── Stage 1: 构建阶段 ──────────────────────────────────────────────────────────
FROM gradle:8.6-jdk17 AS builder

WORKDIR /app

# 先复制 Gradle 配置文件，利用 Docker 层缓存加速依赖下载
COPY settings.gradle build.gradle ./
COPY a2a-proto/build.gradle       a2a-proto/
COPY a2a-registry/build.gradle    a2a-registry/
COPY a2a-core/build.gradle        a2a-core/

# 预下载依赖（缓存层）
RUN gradle dependencies --no-daemon || true

# 复制全部源码并构建
COPY . .
RUN gradle :a2a-registry:bootJar --no-daemon -x test

# ── Stage 2: 运行阶段（最小镜像） ─────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# 创建非 root 用户，提升安全性
RUN addgroup -S a2a && adduser -S a2a -G a2a
USER a2a

COPY --from=builder /app/a2a-registry/build/libs/*.jar app.jar

# HTTP (Actuator / REST) + gRPC
EXPOSE 8080 9090

ENTRYPOINT ["java", "-jar", "app.jar"]
