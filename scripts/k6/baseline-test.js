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

// 测试配置
export const options = {
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
    insecureSkipTLSVerify: true,  // 跳过 TLS 验证（测试用）
};

// 代理服务器地址（根据实际配置修改）
const PROXY_URL = __ENV.PROXY_URL || 'https://localhost:8443';

export default function () {
    // 发送 GET 请求
    const response = http.get(`${PROXY_URL}/api/test`, {
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
        'baseline-results.json': JSON.stringify(data, null, 2),
    };
}

function textSummary(data, opts) {
    const indent = opts.indent || '';
    let output = '\n=== JSSLProxy 基础性能测试结果 ===\n\n';

    output += `${indent}总请求数: ${data.metrics.http_reqs.values.count}\n`;
    output += `${indent}请求率: ${data.metrics.http_reqs.values.rate.toFixed(2)} req/s\n`;
    output += `${indent}平均延迟: ${data.metrics.http_req_duration.values.avg.toFixed(2)} ms\n`;
    output += `${indent}P95 延迟: ${data.metrics.http_req_duration.values['p(95)'].toFixed(2)} ms\n`;
    output += `${indent}P99 延迟: ${data.metrics.http_req_duration.values['p(99)'].toFixed(2)} ms\n`;
    output += `${indent}错误率: ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)}%\n`;

    return output;
}
