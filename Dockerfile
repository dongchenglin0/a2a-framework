# ── Stage 1: 构建阶段 ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# 先只复制 Gradle 配置文件，利用 Docker 层缓存加速依赖下载
COPY gradlew.bat gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY a2a-proto/build.gradle ./a2a-proto/
COPY a2a-registry/build.gradle ./a2a-registry/
COPY a2a-core/build.gradle ./a2a-core/
COPY a2a-demo/build.gradle ./a2a-demo/

# 给 gradlew 执行权限（Linux 容器内）
RUN chmod +x gradlew 2>/dev/null || true

# 下载依赖（利用缓存层）
RUN ./gradlew :a2a-registry:dependencies --no-daemon -q 2>/dev/null || true

# 复制全部源码并构建
COPY . .
RUN ./gradlew :a2a-registry:bootJar --no-daemon -x test

# ── Stage 2: 运行阶段（最小镜像） ─────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# 创建非 root 用户运行
RUN addgroup -S a2a && adduser -S a2a -G a2a

# 从构建阶段复制 jar
COPY --from=builder /app/a2a-registry/build/libs/*.jar app.jar

# 切换到非 root 用户
USER a2a

# 暴露 gRPC 端口和 Spring Boot 管理端口
EXPOSE 9090 8080

# 启动参数（可通过环境变量覆盖）
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
