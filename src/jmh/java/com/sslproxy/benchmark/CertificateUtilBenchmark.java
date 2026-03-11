package com.sslproxy.benchmark;

import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import com.sslproxy.util.CertificateUtil;

/**
 * 证书工具性能基准测试
 *
 * 测试证书指纹计算、过期检查等操作的性能
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class CertificateUtilBenchmark {

    private byte[] sampleCertData;
    private MessageDigest sha256Digest;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        // 创建模拟证书数据
        sampleCertData = new byte[2048];
        for (int i = 0; i < sampleCertData.length; i++) {
            sampleCertData[i] = (byte) (i % 256);
        }

        sha256Digest = MessageDigest.getInstance("SHA-256");
    }

    /**
     * 测试 SHA-256 哈希计算性能
     */
    @Benchmark
    public void testSha256Hash(Blackhole blackhole) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(sampleCertData);
        blackhole.consume(hash);
    }

    /**
     * 测试复用 MessageDigest 的性能
     */
    @Benchmark
    public void testSha256HashReused(Blackhole blackhole) {
        sha256Digest.reset();
        byte[] hash = sha256Digest.digest(sampleCertData);
        blackhole.consume(hash);
    }

    /**
     * 测试指纹格式化性能（字节数组转十六进制字符串）
     */
    @Benchmark
    public void testFingerprintFormatting(Blackhole blackhole) {
        sha256Digest.reset();
        byte[] hash = sha256Digest.digest(sampleCertData);
        String formatted = formatFingerprint(hash);
        blackhole.consume(formatted);
    }

    /**
     * 测试完整指纹计算流程
     */
    @Benchmark
    public void testFullFingerprintCalculation(Blackhole blackhole) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(sampleCertData);
        String formatted = formatFingerprint(hash);
        blackhole.consume(formatted);
    }

    /**
     * 测试 SHA-1 哈希性能（对比用）
     */
    @Benchmark
    public void testSha1Hash(Blackhole blackhole) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] hash = digest.digest(sampleCertData);
        blackhole.consume(hash);
    }

    /**
     * 测试指纹比较性能
     */
    @Benchmark
    public void testFingerprintComparison(Blackhole blackhole, ComparisonState state) {
        boolean equals = state.fingerprint1.equalsIgnoreCase(state.fingerprint2);
        blackhole.consume(equals);
    }

    @State(Scope.Thread)
    public static class ComparisonState {
        String fingerprint1 = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";
        String fingerprint2 = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";
    }

    private String formatFingerprint(byte[] hash) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hash.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format("%02X", hash[i]));
        }
        return sb.toString();
    }
}
