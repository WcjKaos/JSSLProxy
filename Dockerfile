# JSSLProxy Dockerfile
# 多阶段构建：构建阶段 + 运行阶段

# ============= 构建阶段 =============
FROM gradle:8.14-jdk17 AS builder

WORKDIR /app

# 复制构建文件并使用镜像内置 Gradle，避免构建时下载 wrapper 分发包
COPY build.gradle.kts .
COPY settings.gradle.kts .

# 下载运行依赖（利用 Docker 缓存层，并在解析失败时直接失败）
RUN gradle resolveRuntimeClasspath --no-daemon

# 复制源代码
COPY src src

# 构建 JAR（跳过测试，生产构建）
RUN gradle jar --no-daemon

# ============= 运行阶段 =============
FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="SSL Proxy Team"
LABEL version="1.0"
LABEL description="JSSLProxy - SSL/TLS Termination Proxy"

# 创建非 root 用户
RUN addgroup -S sslproxy && adduser -S sslproxy -G sslproxy

WORKDIR /app

# 从构建阶段复制 JAR
COPY --from=builder /app/build/libs/JSSLProxy-1.0-SNAPSHOT.jar app.jar

# 创建配置和证书目录
RUN mkdir -p /app/config /app/certs && \
    chown -R sslproxy:sslproxy /app

# 复制默认配置（可被挂载覆盖）
# COPY config/application.yml /app/config/

# 切换到非 root 用户
USER sslproxy

# 暴露端口
# 代理端口（SSL）。默认配置中的 server.port 为 18443。
EXPOSE 18443
# 管理端口（HTTP）
EXPOSE 9090

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:9090/health || exit 1

# JVM 优化参数
ENV JAVA_OPTS="-XX:+UseG1GC \
    -XX:MaxGCPauseMillis=100 \
    -XX:+UseStringDeduplication \
    -XX:+ExitOnOutOfMemoryError \
    -Djava.security.egd=file:/dev/./urandom"

# 默认内存限制（可通过环境变量覆盖）
ENV JVM_MEMORY_OPTS="-Xms256m -Xmx512m"

# 配置文件路径（可通过环境变量覆盖）
ENV CONFIG_PATH="/app/config/application.yml"

# 启动命令。默认运行代理；传入参数时可直接执行 CLI，例如：docker run image ctl --help
ENTRYPOINT ["sh", "-c", "if [ \"$#\" -eq 0 ]; then set -- \"$CONFIG_PATH\"; fi; exec java $JAVA_OPTS $JVM_MEMORY_OPTS -jar app.jar \"$@\"", "jsslproxy"]
