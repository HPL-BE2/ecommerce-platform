/**
 * 지갑 충전 API 부하 테스트
 *
 * 목적:
 * - 비관적 락 성능 측정
 * - 멱등성 검증
 * - 동시 충전 요청 처리 능력
 *
 * 실행:
 * k6 run load-tests/scenarios/03-wallet-topup.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { BASE_URL, HEADERS, THRESHOLDS, SCENARIOS, TEST_DATA } from '../utils/config.js';
import { randomInt, randomSleep, generateIdempotencyKey, logError } from '../utils/helpers.js';

// 커스텀 메트릭
const errorRate = new Rate('wallet_topup_errors');
const responseTime = new Trend('wallet_topup_duration');
const idempotentCount = new Counter('wallet_topup_idempotent');
const successCount = new Counter('wallet_topup_success');

export const options = {
  stages: SCENARIOS.BASELINE.stages,
  thresholds: {
    ...THRESHOLDS.WRITE,
    'wallet_topup_errors': ['rate<0.01'],
    'wallet_topup_duration': ['p(95)<500'],
  },
};

export default function () {
  const userId = randomInt(1, TEST_DATA.USER_COUNT);
  const amount = randomInt(1, 10) * 10000; // 10,000 ~ 100,000원
  const idempotencyKey = generateIdempotencyKey('topup');

  const payload = JSON.stringify({
    amount: amount,
    idempotencyKey: idempotencyKey,
    refType: 'LOAD_TEST',
    refId: `test-${__VU}-${__ITER}`,
  });

  const params = {
    headers: {
      ...HEADERS,
      'Idempotency-Key': idempotencyKey,
    },
  };

  const url = `${BASE_URL}/api/v1/wallets/${userId}/topups`;

  const startTime = Date.now();
  const res = http.post(url, payload, params);
  const duration = Date.now() - startTime;

  // 응답 검증
  const success = check(res, {
    'status is 200': (r) => r.status === 200,
    'has transaction data': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.data && body.data.transactionId !== undefined;
      } catch (e) {
        return false;
      }
    },
    'has balance after': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.data.balanceAfter !== undefined;
      } catch (e) {
        return false;
      }
    },
    'response time < 1s': (r) => r.timings.duration < 1000,
  });

  // 멱등성 체크
  if (success) {
    try {
      const body = JSON.parse(res.body);
      if (body.data.idempotent === true) {
        idempotentCount.add(1);
      }
      successCount.add(1);
    } catch (e) {
      // ignore
    }
  }

  // 메트릭 기록
  errorRate.add(!success);
  responseTime.add(duration);

  if (!success) {
    logError(res, `POST /api/v1/wallets/${userId}/topups`);
  }

  // Think Time
  sleep(randomSleep(1, 2));
}

export function handleSummary(data) {
  const summary = [];
  summary.push('\n' + '='.repeat(60));
  summary.push('Wallet Top-up API - Load Test Summary');
  summary.push('='.repeat(60));

  const metrics = data.metrics;

  if (metrics.wallet_topup_success && metrics.wallet_topup_idempotent) {
    const totalSuccess = metrics.wallet_topup_success.values.count;
    const idempotentCount = metrics.wallet_topup_idempotent.values.count;
    const idempotentRate = totalSuccess > 0 ? (idempotentCount / totalSuccess * 100).toFixed(2) : 0;
    summary.push(`Total Successful Topups: ${totalSuccess}`);
    summary.push(`Idempotent Responses: ${idempotentCount} (${idempotentRate}%)`);
  }

  if (metrics.wallet_topup_errors) {
    const errorRate = (metrics.wallet_topup_errors.values.rate * 100).toFixed(2);
    summary.push(`Error Rate: ${errorRate}%`);
  }

  if (metrics.wallet_topup_duration) {
    summary.push(`Avg Response Time: ${metrics.wallet_topup_duration.values.avg.toFixed(2)}ms`);
    summary.push(`P95 Response Time: ${metrics.wallet_topup_duration.values['p(95)'].toFixed(2)}ms`);
    summary.push(`P99 Response Time: ${metrics.wallet_topup_duration.values['p(99)'].toFixed(2)}ms`);
  }

  if (metrics.http_reqs) {
    summary.push(`TPS: ${metrics.http_reqs.values.rate.toFixed(2)} req/s`);
  }

  summary.push('='.repeat(60) + '\n');

  return {
    'stdout': summary.join('\n'),
  };
}
