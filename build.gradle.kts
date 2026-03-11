plugins {
    id("java")
    id("application")
    id("me.champeau.jmh") version "0.7.2"
}

group = "com.chaosguide"
version = "1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    // Netty - 异步 IO 框架
    implementation("io.netty:netty-all:4.2.9.Final")
    
    // 日志框架
    implementation("ch.qos.logback:logback-classic:1.5.19")
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")
    
    // YAML 配置解析
    implementation("org.yaml:snakeyaml:2.5")
    
    // 工具库
    implementation("org.apache.commons:commons-lang3:3.19.0")

    // 监控指标 - Micrometer + Prometheus
    implementation("io.micrometer:micrometer-core:1.14.5")
    implementation("io.micrometer:micrometer-registry-prometheus:1.14.5")

    // 限流 - Bucket4j
    implementation("com.bucket4j:bucket4j_jdk17-core:8.16.0")

    // 缓存 - Caffeine (用于限流桶 LRU 清理)
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // JSON 序列化
    implementation("com.google.code.gson:gson:2.12.1")

    // Bouncy Castle - OCSP/CRL 完整实现
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.83")

    // Resilience4j - 熔断器
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.2.0")
    implementation("io.github.resilience4j:resilience4j-micrometer:2.2.0")

    // 测试框架
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Mockito 模拟框架
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito:mockito-junit-jupiter:5.14.2")

    // HTTP 客户端 - 用于集成测试
    testImplementation("org.apache.httpcomponents.client5:httpclient5:5.3")

    // AssertJ - 流式断言
    testImplementation("org.assertj:assertj-core:3.24.2")

    // Awaitility - 异步测试工具
    testImplementation("org.awaitility:awaitility:4.2.0")

    // JMH - 微基准测试
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmh("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

application {
    mainClass.set("com.sslproxy.ProxyApplication")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "com.sslproxy.ProxyApplication",
            "Implementation-Title" to "Java SSL Proxy",
            "Implementation-Version" to version
        )
    }

    // 创建 fat JAR (包含所有依赖)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// JMH 基准测试配置
jmh {
    warmupIterations.set(2)
    iterations.set(3)
    fork.set(1)
    resultFormat.set("JSON")
    resultsFile.set(project.file("build/reports/jmh/results.json"))
}