/**
 * JSSLProxy 突发流量测试
 *
 * 测试代理服务器处理突发流量峰值的能力
 *
 * 运行方式：k6 run spike-test.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const requestDuration = new Trend('request_duration');
const requestErrors = new Counter('request_errors');
const successRate = new Rate('success_rate');

// 突发流量测试配置
export const options = {
    stages: [
        { duration: '30s', target: 10 },   // 正常负载
        { duration: '10s', target: 300 },  // 突然增加到300个VU
        { duration: '1m', target: 300 },   // 保持高负载1分钟
        { duration: '10s', target: 10 },   // 突然降回正常
        { duration: '30s', target: 10 },   // 恢复正常1分钟
        { duration: '10s', target: 500 },  // 再次突增到500
        { duration: '30s', target: 500 },  // 保持30秒
        { duration: '30s', target: 0 },    // 冷却
    ],
    thresholds: {
        http_req_duration: ['p(95)<3000'],   // 突发测试允许更高延迟
        http_req_failed: ['rate<0.15'],       // 允许更高错误率
    },
    insecureSkipTLSVerify: true,
};

const PROXY_URL = __ENV.PROXY_URL || 'https://localhost:8443';

export default function () {
    const response = http.get(`${PROXY_URL}/api/spike-test`, {
        headers: {
            'Accept': 'application/json',
            'X-Request-Id': `spike-${__VU}-${__ITER}`,
        },
        timeout: '30s',
    });

    requestDuration.add(response.timings.duration);

    const checkResult = check(response, {
        'status is 200 or 429 or 503': (r) => [200, 429, 503].includes(r.status),
    });

    if (!checkResult || response.status !== 200) {
        requestErrors.add(1);
        successRate.add(0);
    } else {
        successRate.add(1);
    }

    sleep(0.05);
}

export function handleSummary(data) {
    return {
        'stdout': textSummary(data),
        'spike-results.json': JSON.stringify(data, null, 2),
    };
}

function textSummary(data) {
    let output = '\n=== JSSLProxy 突发流量测试结果 ===\n\n';

    output += `总请求数: ${data.metrics.http_reqs.values.count}\n`;
    output += `请求率: ${data.metrics.http_reqs.values.rate.toFixed(2)} req/s\n`;
    output += `平均延迟: ${data.metrics.http_req_duration.values.avg.toFixed(2)} ms\n`;
    output += `P95 延迟: ${data.metrics.http_req_duration.values['p(95)'].toFixed(2)} ms\n`;
    output += `P99 延迟: ${data.metrics.http_req_duration.values['p(99)'].toFixed(2)} ms\n`;
    output += `错误率: ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)}%\n`;

    return output;
}
