/**
 * 주문 생성 API 부하 테스트
 *
 * 목적:
 * - 가장 복잡한 트랜잭션 성능 측정
 * - 분산 락 경합 및 동시성 제어 검증
 * - 재고 부족, 쿠폰 소진 등 예외 상황 처리
 * - Breaking Point 파악
 *
 * 실행:
 * k6 run load-tests/scenarios/04-order-create.js
 * k6 run --env SCENARIO=stress load-tests/scenarios/04-order-create.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { BASE_URL, HEADERS, THRESHOLDS, SCENARIOS, TEST_DATA } from '../utils/config.js';
import { randomInt, randomSleep, generateIdempotencyKey, logError } from '../utils/helpers.js';

// 커스텀 메트릭
const errorRate = new Rate('order_create_errors');
const responseTime = new Trend('order_create_duration');
const successCount = new Counter('order_create_success');
const stockErrorCount = new Counter('order_stock_errors');
const walletErrorCount = new Counter('order_wallet_errors');

const scenario = __ENV.SCENARIO || 'baseline';

export const options = {
  stages: SCENARIOS[scenario.toUpperCase()]?.stages || SCENARIOS.BASELINE.stages,
  thresholds: {
    ...THRESHOLDS.COMPLEX,
    'order_create_errors': ['rate<0.05'], // 5% 이하 (재고 부족 등 예상 가능한 에러 포함)
    'order_create_duration': ['p(95)<2000'],
  },
};

export default function () {
  const userId = randomInt(1, TEST_DATA.USER_COUNT);
  const productId = randomInt(1, TEST_DATA.PRODUCT_COUNT);
  const qty = randomInt(1, 3);
  const idempotencyKey = generateIdempotencyKey('order');

  // 주문 데이터 생성
  const payload = JSON.stringify({
    userId: userId,
    items: [
      {
        productId: productId,
        qty: qty,
      },
    ],
    couponCode: Math.random() > 0.7 ? 'WELCOME10' : null, // 30% 확률로 쿠폰 사용
    expectedTotal: 50000 * qty, // 간단한 계산 (실제로는 상품 가격 조회 필요)
  });

  const params = {
    headers: {
      ...HEADERS,
      'Idempotency-Key': idempotencyKey,
    },
  };

  const url = `${BASE_URL}/api/v1/orders`;

  const startTime = Date.now();
  const res = http.post(url, payload, params);
  const duration = Date.now() - startTime;

  // 응답 검증
  const success = check(res, {
    'status is 201': (r) => r.status === 201,
    'has order id': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.data && body.data.orderId !== undefined;
      } catch (e) {
        return false;
      }
    },
    'has total': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.data.total !== undefined;
      } catch (e) {
        return false;
      }
    },
    'response time < 5s': (r) => r.timings.duration < 5000,
  });

  // 에러 분류
  if (!success) {
    if (res.status === 400 || res.status === 409) {
      // 비즈니스 로직 에러 (재고 부족, 잔액 부족 등)
      try {
        const body = JSON.parse(res.body);
        if (body.error && body.error.includes('재고')) {
          stockErrorCount.add(1);
        } else if (body.error && body.error.includes('잔액')) {
          walletErrorCount.add(1);
        }
      } catch (e) {
        // ignore
      }
    } else if (res.status >= 500) {
      // 서버 에러 (이것이 진짜 문제!)
      console.error(`[CRITICAL] Server error: ${res.status} - ${res.body}`);
    }
  } else {
    successCount.add(1);
  }

  // 메트릭 기록
  errorRate.add(!success);
  responseTime.add(duration);

  if (!success && res.status >= 500) {
    logError(res, 'POST /api/v1/orders');
  }

  // Think Time (주문은 신중하게)
  sleep(randomSleep(2, 5));
}

export function handleSummary(data) {
  const summary = [];
  summary.push('\n' + '='.repeat(60));
  summary.push('Order Creation API - Load Test Summary');
  summary.push('='.repeat(60));

  const metrics = data.metrics;

  if (metrics.order_create_success) {
    summary.push(`Successful Orders: ${metrics.order_create_success.values.count}`);
  }

  if (metrics.order_stock_errors) {
    summary.push(`Stock Shortage Errors: ${metrics.order_stock_errors.values.count}`);
  }

  if (metrics.order_wallet_errors) {
    summary.push(`Wallet Insufficient Errors: ${metrics.order_wallet_errors.values.count}`);
  }

  if (metrics.order_create_errors) {
    const errorRate = (metrics.order_create_errors.values.rate * 100).toFixed(2);
    summary.push(`Total Error Rate: ${errorRate}%`);
  }

  if (metrics.order_create_duration) {
    summary.push(`Avg Response Time: ${metrics.order_create_duration.values.avg.toFixed(2)}ms`);
    summary.push(`P95 Response Time: ${metrics.order_create_duration.values['p(95)'].toFixed(2)}ms`);
    summary.push(`P99 Response Time: ${metrics.order_create_duration.values['p(99)'].toFixed(2)}ms`);
    summary.push(`Max Response Time: ${metrics.order_create_duration.values.max.toFixed(2)}ms`);
  }

  if (metrics.http_reqs) {
    summary.push(`TPS: ${metrics.http_reqs.values.rate.toFixed(2)} req/s`);
  }

  summary.push('='.repeat(60) + '\n');

  return {
    'stdout': summary.join('\n'),
  };
}
