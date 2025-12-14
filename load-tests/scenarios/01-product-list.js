/**
 * 상품 목록 조회 API 부하 테스트
 *
 * 목적:
 * - 읽기 성능 기준선 확립
 * - Redis 캐시 효과 검증
 * - 최대 조회 TPS 측정
 *
 * 실행:
 * k6 run load-tests/scenarios/01-product-list.js
 * k6 run --env SCENARIO=peak load-tests/scenarios/01-product-list.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { BASE_URL, THRESHOLDS, SCENARIOS } from '../utils/config.js';
import { randomInt, randomSleep, logError } from '../utils/helpers.js';

// 커스텀 메트릭
const errorRate = new Rate('product_list_errors');
const responseTime = new Trend('product_list_duration');
const requestCount = new Counter('product_list_requests');

// 테스트 설정
const scenario = __ENV.SCENARIO || 'baseline';

export const options = {
  stages: SCENARIOS[scenario.toUpperCase()]?.stages || SCENARIOS.BASELINE.stages,
  thresholds: {
    ...THRESHOLDS.READ,
    'product_list_errors': ['rate<0.001'],
    'product_list_duration': ['p(95)<300'],
  },
};

export default function () {
  // 랜덤 검색 조건
  const searchQuery = Math.random() > 0.7 ? `?q=${getRandomSearchTerm()}` : '';
  const categoryId = Math.random() > 0.5 ? `${searchQuery ? '&' : '?'}categoryId=${randomInt(1, 5)}` : '';
  const limit = `${(searchQuery || categoryId) ? '&' : '?'}limit=${randomInt(10, 50)}`;

  const url = `${BASE_URL}/api/v1/products${searchQuery}${categoryId}${limit}`;

  const startTime = Date.now();
  const res = http.get(url);
  const duration = Date.now() - startTime;

  // 응답 검증
  const success = check(res, {
    'status is 200': (r) => r.status === 200,
    'has data': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.data !== undefined;
      } catch (e) {
        return false;
      }
    },
    'response time < 500ms': (r) => r.timings.duration < 500,
    'has pagination': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.data.hasNext !== undefined;
      } catch (e) {
        return false;
      }
    },
  });

  // 메트릭 기록
  errorRate.add(!success);
  responseTime.add(duration);
  requestCount.add(1);

  // 에러 로깅
  if (!success) {
    logError(res, 'GET /api/v1/products');
  }

  // Think Time (사용자가 결과를 보는 시간)
  sleep(randomSleep(0.5, 2));
}

// 헬퍼 함수
function getRandomSearchTerm() {
  const terms = ['shirt', 'pants', 'shoes', 'jacket', 'hat', 'bag', 'watch', 'phone'];
  return terms[Math.floor(Math.random() * terms.length)];
}

// 테스트 종료 시 요약
export function handleSummary(data) {
  return {
    'stdout': textSummary(data, { indent: ' ', enableColors: true }),
  };
}

function textSummary(data, options) {
  const summary = [];
  summary.push('\n' + '='.repeat(60));
  summary.push('Product List API - Load Test Summary');
  summary.push('='.repeat(60));

  const metrics = data.metrics;

  if (metrics.product_list_requests) {
    summary.push(`Total Requests: ${metrics.product_list_requests.values.count}`);
  }

  if (metrics.product_list_errors) {
    const errorRate = (metrics.product_list_errors.values.rate * 100).toFixed(2);
    summary.push(`Error Rate: ${errorRate}%`);
  }

  if (metrics.product_list_duration) {
    summary.push(`Avg Response Time: ${metrics.product_list_duration.values.avg.toFixed(2)}ms`);
    summary.push(`P95 Response Time: ${metrics.product_list_duration.values['p(95)'].toFixed(2)}ms`);
    summary.push(`P99 Response Time: ${metrics.product_list_duration.values['p(99)'].toFixed(2)}ms`);
  }

  if (metrics.http_reqs) {
    summary.push(`TPS: ${metrics.http_reqs.values.rate.toFixed(2)} req/s`);
  }

  summary.push('='.repeat(60) + '\n');

  return summary.join('\n');
}
