/**
 * 상품 상세 조회 API 부하 테스트
 *
 * 목적:
 * - 캐시 히트율 측정 (Redis 10분 TTL)
 * - Hot Item 성능 검증
 * - 단일 상품 조회 성능
 *
 * 실행:
 * k6 run load-tests/scenarios/02-product-detail.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { BASE_URL, THRESHOLDS, SCENARIOS, TEST_DATA } from '../utils/config.js';
import { randomInt, randomSleep, logError } from '../utils/helpers.js';

// 커스텀 메트릭
const errorRate = new Rate('product_detail_errors');
const responseTime = new Trend('product_detail_duration');
const cacheHitRate = new Rate('product_detail_cache_hits');

export const options = {
  stages: SCENARIOS.BASELINE.stages,
  thresholds: {
    ...THRESHOLDS.READ,
    'product_detail_errors': ['rate<0.001'],
    'product_detail_duration': ['p(95)<150'],
    'product_detail_cache_hits': ['rate>0.8'], // 80% 이상 캐시 히트 기대
  },
};

export default function () {
  // Hot Item 시뮬레이션: 20% 확률로 인기 상품 (1~5), 80% 확률로 일반 상품
  let productId;
  if (Math.random() < 0.2) {
    productId = randomInt(1, 5); // Hot items
  } else {
    productId = randomInt(6, TEST_DATA.PRODUCT_COUNT); // Regular items
  }

  const url = `${BASE_URL}/api/v1/products/${productId}`;

  const startTime = Date.now();
  const res = http.get(url);
  const duration = Date.now() - startTime;

  // 캐시 히트 여부 (응답 시간으로 추정)
  const cacheHit = res.timings.duration < 50; // 50ms 이하면 캐시 히트로 간주
  cacheHitRate.add(cacheHit);

  // 응답 검증
  const success = check(res, {
    'status is 200': (r) => r.status === 200,
    'has product data': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.data && body.data.id === productId;
      } catch (e) {
        return false;
      }
    },
    'has price': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.data.unitPrice !== undefined;
      } catch (e) {
        return false;
      }
    },
    'response time < 300ms': (r) => r.timings.duration < 300,
  });

  // 메트릭 기록
  errorRate.add(!success);
  responseTime.add(duration);

  if (!success) {
    logError(res, `GET /api/v1/products/${productId}`);
  }

  // Think Time
  sleep(randomSleep(1, 3));
}

export function handleSummary(data) {
  const summary = [];
  summary.push('\n' + '='.repeat(60));
  summary.push('Product Detail API - Load Test Summary');
  summary.push('='.repeat(60));

  const metrics = data.metrics;

  if (metrics.product_detail_cache_hits) {
    const cacheHitRate = (metrics.product_detail_cache_hits.values.rate * 100).toFixed(2);
    summary.push(`Cache Hit Rate: ${cacheHitRate}%`);
  }

  if (metrics.product_detail_errors) {
    const errorRate = (metrics.product_detail_errors.values.rate * 100).toFixed(2);
    summary.push(`Error Rate: ${errorRate}%`);
  }

  if (metrics.product_detail_duration) {
    summary.push(`Avg Response Time: ${metrics.product_detail_duration.values.avg.toFixed(2)}ms`);
    summary.push(`P95 Response Time: ${metrics.product_detail_duration.values['p(95)'].toFixed(2)}ms`);
  }

  if (metrics.http_reqs) {
    summary.push(`TPS: ${metrics.http_reqs.values.rate.toFixed(2)} req/s`);
  }

  summary.push('='.repeat(60) + '\n');

  return {
    'stdout': summary.join('\n'),
  };
}
