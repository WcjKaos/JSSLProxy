# JSSLProxy 性能测试指南

## 概述

本目录包含 JSSLProxy 的性能测试脚本，使用 K6 进行 HTTP 负载测试。

## 前置条件

1. 安装 K6：https://k6.io/docs/getting-started/installation/
2. 启动 JSSLProxy 服务器

## 测试脚本

### 1. 基础性能测试 (baseline-test.js)

测试正常负载下的性能表现。

```bash
k6 run baseline-test.js
```

**测试场景：**
- 30秒预热到10个虚拟用户
- 1分钟保持50个虚拟用户
- 30秒增加到100个虚拟用户
- 1分钟峰值负载
- 30秒冷却

**通过标准：**
- P95 延迟 < 500ms
- 错误率 < 1%

### 2. 压力测试 (stress-test.js)

测试极端负载下的系统极限。

```bash
k6 run stress-test.js
```

**测试场景：**
- 逐步增加到500个虚拟用户
- 观察系统在极限下的表现

**通过标准：**
- P95 延迟 < 2000ms
- 错误率 < 10%

### 3. 突发流量测试 (spike-test.js)

测试突发流量峰值的处理能力。

```bash
k6 run spike-test.js
```

**测试场景：**
- 正常负载下突然增加到300-500个虚拟用户
- 观察系统的恢复能力

### 4. 持续负载测试 (soak-test.js)

测试长时间运行的稳定性。

```bash
k6 run soak-test.js
```

**测试场景：**
- 50个虚拟用户持续运行30分钟
- 检测内存泄漏和资源耗尽问题

**通过标准：**
- 整个测试期间 P95 延迟稳定 < 500ms
- 错误率始终 < 1%

## 自定义配置

可以通过环境变量自定义代理服务器地址：

```bash
k6 run -e PROXY_URL=https://your-proxy:8443 baseline-test.js
```

## 测试结果

每个测试会生成 JSON 格式的结果文件：
- `baseline-results.json`
- `stress-results.json`
- `spike-results.json`
- `soak-results.json`

## 性能目标

| 指标 | 目标值 |
|------|--------|
| 吞吐量 | > 10,000 RPS (单节点) |
| P99 延迟 | < 50ms |
| 内存占用 | < 512MB (10K 并发) |
| CPU 使用率 | < 70% (满负载) |
