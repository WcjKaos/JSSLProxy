# JSSLProxy Dockerfile
# 多阶段构建：构建阶段 + 运行阶段

# ============= 构建阶段 =============
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# 复制 Gradle Wrapper 和构建文件
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .

# 授予执行权限
RUN chmod +x gradlew

# 下载依赖（利用 Docker 缓存层）
RUN ./gradlew dependencies --no-daemon

# 复制源代码
COPY src src

# 构建 JAR（跳过测试，生产构建）
RUN ./gradlew jar --no-daemon

# ============= 运行阶段 =============
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="SSL Proxy Team"
LABEL version="1.0"
LABEL description="JSSLProxy - SSL/TLS Termination Proxy"

# 创建非 root 用户
RUN addgroup -S sslproxy && adduser -S sslproxy -G sslproxy

WORKDIR /app

# 从构建阶段复制 JAR
COPY --from=builder /app/build/libs/*-all.jar app.jar

# 创建配置和证书目录
RUN mkdir -p /app/config /app/certs && \
    chown -R sslproxy:sslproxy /app

# 复制默认配置（可被挂载覆盖）
# COPY config/application.yml /app/config/

# 切换到非 root 用户
USER sslproxy

# 暴露端口
# 代理端口（SSL）
EXPOSE 8443
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

# 启动命令
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS $JVM_MEMORY_OPTS -jar app.jar $CONFIG_PATH"]
