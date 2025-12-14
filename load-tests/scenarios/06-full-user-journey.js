/**
 * 전체 사용자 여정 테스트
 *
 * 시나리오:
 * 1. 상품 목록 조회
 * 2. 상품 상세 조회 (여러 개)
 * 3. 지갑 충전
 * 4. 주문 생성
 * 5. 주문 완료
 *
 * 목적:
 * - 실제 사용자 행동 패턴 시뮬레이션
 * - End-to-End 성능 측정
 * - 전체 시스템 부하 테스트
 *
 * 실행:
 * k6 run load-tests/scenarios/06-full-user-journey.js
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { BASE_URL, HEADERS, SCENARIOS, TEST_DATA } from '../utils/config.js';
import { randomInt, randomSleep, generateIdempotencyKey, logError } from '../utils/helpers.js';

// 커스텀 메트릭
const journeySuccessRate = new Rate('journey_success');
const journeyDuration = new Trend('journey_duration');
const stepErrors = new Counter('journey_step_errors');

export const options = {
  stages: SCENARIOS.BASELINE.stages,
  thresholds: {
    'journey_success': ['rate>0.9'],                    // 90% 이상 성공
    'journey_duration': ['p(95)<10000'],                // 전체 여정 10초 이내
    'http_req_duration{group:::1. Browse Products}': ['p(95)<500'],
    'http_req_duration{group:::2. View Details}': ['p(95)<500'],
    'http_req_duration{group:::3. Top-up Wallet}': ['p(95)<1000'],
    'http_req_duration{group:::4. Create Order}': ['p(95)<2000'],
    'http_req_duration{group:::5. Complete Order}': ['p(95)<1000'],
  },
};

export default function () {
  const userId = randomInt(1, TEST_DATA.USER_COUNT);
  const journeyStart = Date.now();
  let journeySuccess = true;

  // Step 1: 상품 목록 조회
  group('1. Browse Products', function () {
    const res = http.get(`${BASE_URL}/api/v1/products?limit=20`);

    const success = check(res, {
      'product list status is 200': (r) => r.status === 200,
      'has products': (r) => {
        try {
          return JSON.parse(r.body).data !== undefined;
        } catch (e) {
          return false;
        }
      },
    });

    if (!success) {
      journeySuccess = false;
      stepErrors.add(1);
      logError(res, 'Browse Products');
    }

    sleep(randomSleep(1, 2)); // 결과 보기
  });

  // Step 2: 상품 상세 조회 (2~3개)
  group('2. View Details', function () {
    const viewCount = randomInt(2, 3);

    for (let i = 0; i < viewCount; i++) {
      const productId = randomInt(1, TEST_DATA.PRODUCT_COUNT);
      const res = http.get(`${BASE_URL}/api/v1/products/${productId}`);

      const success = check(res, {
        'product detail status is 200': (r) => r.status === 200,
      });

      if (!success) {
        journeySuccess = false;
        stepErrors.add(1);
        logError(res, `View Product ${productId}`);
      }

      sleep(randomSleep(1, 3)); // 상세 정보 읽기
    }
  });

  // Step 3: 지갑 충전 (잔액 확인 후 필요 시)
  group('3. Top-up Wallet', function () {
    const amount = 100000; // 10만원 충전
    const idempotencyKey = generateIdempotencyKey('topup');

    const payload = JSON.stringify({
      amount: amount,
      idempotencyKey: idempotencyKey,
      refType: 'USER_JOURNEY',
      refId: `journey-${__VU}-${__ITER}`,
    });

    const params = {
      headers: {
        ...HEADERS,
        'Idempotency-Key': idempotencyKey,
      },
    };

    const res = http.post(`${BASE_URL}/api/v1/wallets/${userId}/topups`, payload, params);

    const success = check(res, {
      'wallet topup status is 200': (r) => r.status === 200,
      'has transaction id': (r) => {
        try {
          return JSON.parse(r.body).data.transactionId !== undefined;
        } catch (e) {
          return false;
        }
      },
    });

    if (!success) {
      journeySuccess = false;
      stepErrors.add(1);
      logError(res, 'Top-up Wallet');
    }

    sleep(1); // 잔액 확인
  });

  // Step 4: 주문 생성
  let orderId = null;

  group('4. Create Order', function () {
    const productId = randomInt(1, TEST_DATA.PRODUCT_COUNT);
    const qty = randomInt(1, 2);
    const idempotencyKey = generateIdempotencyKey('order');

    const payload = JSON.stringify({
      userId: userId,
      items: [
        {
          productId: productId,
          qty: qty,
        },
      ],
      expectedTotal: 50000 * qty,
    });

    const params = {
      headers: {
        ...HEADERS,
        'Idempotency-Key': idempotencyKey,
      },
    };

    const res = http.post(`${BASE_URL}/api/v1/orders`, payload, params);

    const success = check(res, {
      'order create status is 201': (r) => r.status === 201,
      'has order id': (r) => {
        try {
          const body = JSON.parse(r.body);
          if (body.data && body.data.orderId) {
            orderId = body.data.orderId;
            return true;
          }
          return false;
        } catch (e) {
          return false;
        }
      },
    });

    if (!success) {
      journeySuccess = false;
      stepErrors.add(1);
      logError(res, 'Create Order');
    }

    sleep(randomSleep(1, 2)); // 주문 확인
  });

  // Step 5: 주문 완료 (orderId가 있을 때만)
  if (orderId) {
    group('5. Complete Order', function () {
      const res = http.patch(`${BASE_URL}/api/v1/orders/${orderId}/complete`);

      const success = check(res, {
        'order complete status is 200': (r) => r.status === 200,
        'has completed order': (r) => {
          try {
            return JSON.parse(r.body).data.orderId !== undefined;
          } catch (e) {
            return false;
          }
        },
      });

      if (!success) {
        journeySuccess = false;
        stepErrors.add(1);
        logError(res, `Complete Order ${orderId}`);
      }

      sleep(1); // 완료 메시지 확인
    });
  } else {
    // 주문 생성 실패 시 완료 단계 스킵
    journeySuccess = false;
  }

  // 전체 여정 메트릭 기록
  const journeyTime = Date.now() - journeyStart;
  journeyDuration.add(journeyTime);
  journeySuccessRate.add(journeySuccess);

  if (journeySuccess) {
    console.log(`[SUCCESS] User ${userId} completed journey in ${journeyTime}ms`);
  } else {
    console.log(`[FAILED] User ${userId} journey failed after ${journeyTime}ms`);
  }

  // 다음 여정까지 대기
  sleep(randomSleep(5, 10));
}

export function handleSummary(data) {
  const summary = [];
  summary.push('\n' + '='.repeat(60));
  summary.push('Full User Journey - Load Test Summary');
  summary.push('='.repeat(60));

  const metrics = data.metrics;

  if (metrics.iterations) {
    summary.push(`Total User Journeys: ${metrics.iterations.values.count}`);
  }

  if (metrics.journey_success) {
    const successRate = (metrics.journey_success.values.rate * 100).toFixed(2);
    summary.push(`Journey Success Rate: ${successRate}%`);
  }

  if (metrics.journey_step_errors) {
    summary.push(`Total Step Errors: ${metrics.journey_step_errors.values.count}`);
  }

  if (metrics.journey_duration) {
    summary.push(`Avg Journey Duration: ${(metrics.journey_duration.values.avg / 1000).toFixed(2)}s`);
    summary.push(`P95 Journey Duration: ${(metrics.journey_duration.values['p(95)'] / 1000).toFixed(2)}s`);
    summary.push(`P99 Journey Duration: ${(metrics.journey_duration.values['p(99)'] / 1000).toFixed(2)}s`);
  }

  summary.push('\nStep-by-step Breakdown:');
  summary.push('1. Browse Products:    ' + getGroupMetric(data, '1. Browse Products'));
  summary.push('2. View Details:       ' + getGroupMetric(data, '2. View Details'));
  summary.push('3. Top-up Wallet:      ' + getGroupMetric(data, '3. Top-up Wallet'));
  summary.push('4. Create Order:       ' + getGroupMetric(data, '4. Create Order'));
  summary.push('5. Complete Order:     ' + getGroupMetric(data, '5. Complete Order'));

  summary.push('='.repeat(60) + '\n');

  return {
    'stdout': summary.join('\n'),
  };
}

function getGroupMetric(data, groupName) {
  const key = `http_req_duration{group:::${groupName}}`;
  if (data.metrics[key]) {
    const avg = data.metrics[key].values.avg.toFixed(0);
    const p95 = data.metrics[key].values['p(95)'].toFixed(0);
    return `Avg ${avg}ms, P95 ${p95}ms`;
  }
  return 'N/A';
}
