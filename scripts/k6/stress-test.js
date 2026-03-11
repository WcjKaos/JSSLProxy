/**
 * JSSLProxy 压力测试
 *
 * 测试代理服务器在极端负载下的表现和极限
 *
 * 运行方式：k6 run stress-test.js
 */

import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// 自定义指标
const requestDuration = new Trend('request_duration');
const requestErrors = new Counter('request_errors');
const successRate = new Rate('success_rate');
const circuitBreakerTrips = new Counter('circuit_breaker_trips');

// 测试配置 - 压力测试
export const options = {
    stages: [
        { duration: '1m', target: 100 },   // 1分钟内增加到100个VU
        { duration: '2m', target: 200 },   // 2分钟内增加到200个VU
        { duration: '2m', target: 300 },   // 2分钟内增加到300个VU
        { duration: '2m', target: 400 },   // 2分钟内增加到400个VU
        { duration: '2m', target: 500 },   // 2分钟内增加到500个VU（极限测试）
        { duration: '1m', target: 0 },     // 1分钟冷却
    ],
    thresholds: {
        http_req_duration: ['p(95)<2000'],   // 压力测试下允许更高延迟
        http_req_failed: ['rate<0.10'],       // 压力测试下允许更高错误率
    },
    insecureSkipTLSVerify: true,
};

const PROXY_URL = __ENV.PROXY_URL || 'https://localhost:8443';

export default function () {
    const response = http.get(`${PROXY_URL}/api/stress-test`, {
        headers: {
            'Accept': 'application/json',
            'X-Request-Id': `stress-${__VU}-${__ITER}`,
        },
        timeout: '60s',
    });

    requestDuration.add(response.timings.duration);

    // 检查是否触发熔断
    if (response.status === 503) {
        circuitBreakerTrips.add(1);
    }

    const checkResult = check(response, {
        'status is 200 or 429 or 503': (r) => [200, 429, 503].includes(r.status),
        'response time < 2000ms': (r) => r.timings.duration < 2000,
    });

    if (!checkResult || response.status !== 200) {
        requestErrors.add(1);
        successRate.add(0);
    } else {
        successRate.add(1);
    }

    // 减少思考时间以增加压力
    sleep(0.05);
}

export function handleSummary(data) {
    return {
        'stdout': textSummary(data),
        'stress-results.json': JSON.stringify(data, null, 2),
    };
}

function textSummary(data) {
    let output = '\n=== JSSLProxy 压力测试结果 ===\n\n';

    output += `总请求数: ${data.metrics.http_reqs.values.count}\n`;
    output += `最大请求率: ${data.metrics.http_reqs.values.rate.toFixed(2)} req/s\n`;
    output += `平均延迟: ${data.metrics.http_req_duration.values.avg.toFixed(2)} ms\n`;
    output += `P95 延迟: ${data.metrics.http_req_duration.values['p(95)'].toFixed(2)} ms\n`;
    output += `P99 延迟: ${data.metrics.http_req_duration.values['p(99)'].toFixed(2)} ms\n`;
    output += `最大延迟: ${data.metrics.http_req_duration.values.max.toFixed(2)} ms\n`;
    output += `错误率: ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)}%\n`;

    if (data.metrics.circuit_breaker_trips) {
        output += `熔断器触发次数: ${data.metrics.circuit_breaker_trips.values.count}\n`;
    }

    return output;
}
