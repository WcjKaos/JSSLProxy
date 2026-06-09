/**
 * JSSLProxy 基础性能测试
 *
 * 测试代理服务器在正常负载下的性能表现
 *
 * 运行方式：k6 run baseline-test.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// 自定义指标
const requestDuration = new Trend('request_duration');
const requestErrors = new Counter('request_errors');
const successRate = new Rate('success_rate');
const quickMode = __ENV.K6_QUICK === 'true';
const tlsCertPath = __ENV.TLS_CERT;
const tlsKeyPath = __ENV.TLS_KEY;
const tlsDomain = __ENV.TLS_DOMAIN || 'localhost';
const summaryPath = __ENV.K6_SUMMARY_PATH || 'baseline-results.json';

const tlsAuth = tlsCertPath && tlsKeyPath ? [{
    domains: [tlsDomain],
    cert: open(tlsCertPath),
    key: open(tlsKeyPath),
}] : [];

// 测试配置
export const options = quickMode ? {
    vus: Number(__ENV.K6_VUS || 5),
    duration: __ENV.K6_DURATION || '20s',
    thresholds: {
        http_req_duration: ['p(95)<1000'],
        http_req_failed: ['rate<0.05'],
        success_rate: ['rate>0.95'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    insecureSkipTLSVerify: true,
    tlsAuth,
} : {
    stages: [
        { duration: '30s', target: 10 },   // 预热：30秒内增加到10个VU
        { duration: '1m', target: 50 },    // 正常负载：1分钟保持50个VU
        { duration: '30s', target: 100 },  // 增加负载：30秒内增加到100个VU
        { duration: '1m', target: 100 },   // 峰值负载：1分钟保持100个VU
        { duration: '30s', target: 0 },    // 冷却：30秒内降到0
    ],
    thresholds: {
        http_req_duration: ['p(95)<500'],    // 95% 请求延迟小于500ms
        http_req_failed: ['rate<0.01'],       // 错误率小于1%
        success_rate: ['rate>0.99'],          // 成功率大于99%
    },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    insecureSkipTLSVerify: true,  // 跳过 TLS 验证（测试用）
    tlsAuth,
};

// 代理服务器地址（根据实际配置修改）
const PROXY_URL = __ENV.PROXY_URL || 'https://localhost:8443';
const PROXY_PATH = __ENV.PROXY_PATH || '/api/test';

export default function () {
    // 发送 GET 请求
    const response = http.get(`${PROXY_URL}${PROXY_PATH}`, {
        headers: {
            'Accept': 'application/json',
            'X-Request-Id': `test-${__VU}-${__ITER}`,
        },
        timeout: '30s',
    });

    // 记录指标
    requestDuration.add(response.timings.duration);

    // 检查响应
    const checkResult = check(response, {
        'status is 200': (r) => r.status === 200,
        'response time < 500ms': (r) => r.timings.duration < 500,
        'response has body': (r) => r.body && r.body.length > 0,
    });

    if (!checkResult) {
        requestErrors.add(1);
        successRate.add(0);
    } else {
        successRate.add(1);
    }

    // 模拟用户思考时间
    sleep(0.1);
}

export function handleSummary(data) {
    return {
        'stdout': textSummary(data, { indent: ' ', enableColors: true }),
        [summaryPath]: JSON.stringify(data, null, 2),
    };
}

function textSummary(data, opts) {
    const indent = opts.indent || '';
    let output = '\n=== JSSLProxy 基础性能测试结果 ===\n\n';
    const duration = data.metrics.http_req_duration || { values: {} };
    const failed = data.metrics.http_req_failed || { values: {} };
    const requests = data.metrics.http_reqs || { values: {} };

    output += `${indent}总请求数: ${formatValue(requests.values.count, 0)}\n`;
    output += `${indent}请求率: ${formatValue(requests.values.rate, 2)} req/s\n`;
    output += `${indent}平均延迟: ${formatValue(duration.values.avg, 2)} ms\n`;
    output += `${indent}P95 延迟: ${formatValue(duration.values['p(95)'], 2)} ms\n`;
    output += `${indent}P99 延迟: ${formatValue(duration.values['p(99)'], 2)} ms\n`;
    output += `${indent}错误率: ${formatValue((failed.values.rate || 0) * 100, 2)}%\n`;

    return output;
}

function formatValue(value, digits) {
    if (value === undefined || value === null || Number.isNaN(value)) {
        return digits === 0 ? '0' : (0).toFixed(digits);
    }
    return digits === 0 ? String(value) : value.toFixed(digits);
}
