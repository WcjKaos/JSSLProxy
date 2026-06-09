package com.sslproxy;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sslproxy.cli.AdminCli;
import com.sslproxy.config.ProxyConfig;
import com.sslproxy.server.ProxyServer;

/**
 * SSL 代理应用程序主入口
 * <p>
 * 加载配置并启动代理服务器。
 * </p>
 * 
 * @author SSL Proxy Team
 * @version 1.0
 */
public class ProxyApplication {

    // Must run before the logger field is initialized (i.e., before LoggerFactory is called).
    // Redirects logback to the JSON config when LOGGING_FORMAT=json is set.
    static {
        String loggingFormat = System.getenv("LOGGING_FORMAT");
        if (loggingFormat == null) {
            loggingFormat = System.getProperty("logging.format", "text");
        }
        if ("json".equalsIgnoreCase(loggingFormat)) {
            System.setProperty("logback.configurationFile", "logback-json.xml");
        }
    }

    public static void main(String[] args) {
        if (args.length > 0 && ("ctl".equals(args[0]) || "admin".equals(args[0]))) {
            int exitCode = AdminCli.execute(Arrays.copyOfRange(args, 1, args.length),
                    System.out, System.err, System.getenv());
            System.exit(exitCode);
        }

        try {
            // 处理帮助参数
            if (args.length > 0 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
                printUsage();
                System.exit(0);
            }

            Logger logger = LoggerFactory.getLogger(ProxyApplication.class);
            logger.info("========================================");
            logger.info("    Java SSL Proxy v1.0");
            logger.info("========================================");

            // 加载配置
            String configPath = args.length > 0 ? args[0] : null;
            ProxyConfig config = ProxyConfig.load(configPath);

            logger.info("Configuration loaded successfully");

            // 创建并启动代理服务器（传递配置文件路径用于热加载）
            ProxyServer server = new ProxyServer(config, configPath);
            server.start();

        } catch (Exception e) {
            Logger logger = LoggerFactory.getLogger(ProxyApplication.class);
            logger.error("Failed to start proxy server: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("JSSLProxy - Java SSL/TLS Termination Proxy");
        System.out.println();
        System.out.println("Usage: java -jar jsslproxy.jar [options] [config-path]");
        System.out.println("       java -jar jsslproxy.jar ctl [admin-cli-options] <command>");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -h, --help    Show this help message");
        System.out.println();
        System.out.println("Arguments:");
        System.out.println("  config-path   Path to application.yml config file");
        System.out.println("                (default: classpath:application.yml)");
        System.out.println();
        System.out.println("Environment Variables:");
        System.out.println("  KEYSTORE_PASSWORD     Override keystore password");
        System.out.println("  TRUSTSTORE_PASSWORD   Override truststore password");
        System.out.println("  LOGGING_FORMAT        Log format: text (default) | json");
    }
}
