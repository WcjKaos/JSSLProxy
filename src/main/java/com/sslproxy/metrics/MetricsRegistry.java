package com.sslproxy.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sslproxy.config.ProxyConfig;

/**
 * 指标注册中心
 * <p>
 * 封装 Micrometer 的 PrometheusMeterRegistry，提供全局单例访问。
 * 支持 JVM 指标的自动绑定和 Prometheus 格式的指标导出。
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class MetricsRegistry {
    private static final Logger logger = LoggerFactory.getLogger(MetricsRegistry.class);

    private final PrometheusMeterRegistry prometheusRegistry;
    private final boolean enabled;

    /**
     * 构造函数
     *
     * @param config 代理配置
     */
    public MetricsRegistry(ProxyConfig config) {
        this.enabled = config.metrics != null && config.metrics.enabled;

        if (enabled) {
            this.prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

            // 绑定 JVM 指标
            if (config.metrics.includeJvmMetrics) {
                bindJvmMetrics();
            }

            logger.info("MetricsRegistry initialized with Prometheus registry");
        } else {
            this.prometheusRegistry = null;
            logger.info("MetricsRegistry disabled");
        }
    }

    /**
     * 获取 MeterRegistry
     *
     * @return MeterRegistry 实例，如果禁用则返回 null
     */
    public MeterRegistry getRegistry() {
        return prometheusRegistry;
    }

    /**
     * 获取 Prometheus 格式的指标数据
     *
     * @return Prometheus 格式的指标字符串
     */
    public String scrape() {
        if (prometheusRegistry != null) {
            return prometheusRegistry.scrape();
        }
        return "";
    }

    /**
     * 检查指标是否启用
     *
     * @return true 如果启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 绑定 JVM 相关指标
     */
    private void bindJvmMetrics() {
        // JVM 内存指标
        new JvmMemoryMetrics().bindTo(prometheusRegistry);

        // JVM GC 指标
        new JvmGcMetrics().bindTo(prometheusRegistry);

        // JVM 线程指标
        new JvmThreadMetrics().bindTo(prometheusRegistry);

        // 类加载器指标
        new ClassLoaderMetrics().bindTo(prometheusRegistry);

        // 处理器指标
        new ProcessorMetrics().bindTo(prometheusRegistry);

        // 运行时间指标
        new UptimeMetrics().bindTo(prometheusRegistry);

        logger.debug("JVM metrics bound to registry");
    }

    /**
     * 关闭注册中心
     */
    public void close() {
        if (prometheusRegistry != null) {
            prometheusRegistry.close();
            logger.info("MetricsRegistry closed");
        }
    }
}
