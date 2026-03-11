/**
 * JSSLProxy 持续负载测试（浸泡测试）
 *
 * 测试代理服务器在长时间运行下的稳定性（内存泄漏、资源耗尽等）
 *
 * 运行方式：k6 run soak-test.js
 * 注意：此测试运行时间较长（默认30分钟）
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend, Gauge } from 'k6/metrics';

const requestDuration = new Trend('request_duration');
const requestErrors = new Counter('request_errors');
const successRate = new Rate('success_rate');

// 持续负载测试配置
export const options = {
    stages: [
        { duration: '2m', target: 50 },    // 预热
        { duration: '25m', target: 50 },   // 持续负载25分钟
        { duration: '3m', target: 0 },     // 冷却
    ],
    thresholds: {
        http_req_duration: ['p(95)<500'],    // 长时间运行下保持低延迟
        http_req_failed: ['rate<0.01'],       // 错误率小于1%
        success_rate: ['rate>0.99'],          // 成功率大于99%
    },
    insecureSkipTLSVerify: true,
};

const PROXY_URL = __ENV.PROXY_URL || 'https://localhost:8443';

// 记录测试开始时间
let testStartTime;

export function setup() {
    testStartTime = Date.now();
    console.log('开始持续负载测试...');
}

export default function () {
    const response = http.get(`${PROXY_URL}/api/soak-test`, {
        headers: {
            'Accept': 'application/json',
            'X-Request-Id': `soak-${__VU}-${__ITER}`,
        },
        timeout: '30s',
    });

    requestDuration.add(response.timings.duration);

    const checkResult = check(response, {
        'status is 200': (r) => r.status === 200,
        'response time < 500ms': (r) => r.timings.duration < 500,
    });

    if (!checkResult) {
        requestErrors.add(1);
        successRate.add(0);
    } else {
        successRate.add(1);
    }

    // 正常思考时间
    sleep(0.2);
}

export function teardown(data) {
    const duration = (Date.now() - testStartTime) / 1000 / 60;
    console.log(`测试持续时间: ${duration.toFixed(2)} 分钟`);
}

export function handleSummary(data) {
    return {
        'stdout': textSummary(data),
        'soak-results.json': JSON.stringify(data, null, 2),
    };
}

function textSummary(data) {
    let output = '\n=== JSSLProxy 持续负载测试结果 ===\n\n';

    output += `总请求数: ${data.metrics.http_reqs.values.count}\n`;
    output += `请求率: ${data.metrics.http_reqs.values.rate.toFixed(2)} req/s\n`;
    output += `平均延迟: ${data.metrics.http_req_duration.values.avg.toFixed(2)} ms\n`;
    output += `P95 延迟: ${data.metrics.http_req_duration.values['p(95)'].toFixed(2)} ms\n`;
    output += `P99 延迟: ${data.metrics.http_req_duration.values['p(99)'].toFixed(2)} ms\n`;
    output += `最大延迟: ${data.metrics.http_req_duration.values.max.toFixed(2)} ms\n`;
    output += `最小延迟: ${data.metrics.http_req_duration.values.min.toFixed(2)} ms\n`;
    output += `错误率: ${(data.metrics.http_req_failed.values.rate * 100).toFixed(4)}%\n`;
    output += `成功率: ${(data.metrics.success_rate.values.rate * 100).toFixed(4)}%\n`;

    output += '\n=== 稳定性评估 ===\n';
    if (data.metrics.http_req_failed.values.rate < 0.01 &&
        data.metrics.http_req_duration.values['p(95)'] < 500) {
        output += '✓ 系统在持续负载下表现稳定\n';
    } else {
        output += '✗ 系统在持续负载下出现性能问题\n';
    }

    return output;
}
