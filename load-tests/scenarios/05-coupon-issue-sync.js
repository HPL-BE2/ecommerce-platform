/**
 * 쿠폰 발급 API (동기) 부하 테스트
 *
 * 목적:
 * - 선착순 이벤트 시뮬레이션
 * - Redis 분산 락 성능 검증
 * - Thundering Herd 대응 능력 측정
 * - 락 타임아웃 및 실패율 분석
 *
 * 실행:
 * k6 run load-tests/scenarios/05-coupon-issue-sync.js
 * k6 run --env SCENARIO=spike load-tests/scenarios/05-coupon-issue-sync.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { BASE_URL, THRESHOLDS, SCENARIOS, TEST_DATA } from '../utils/config.js';
import { randomInt, logError } from '../utils/helpers.js';

// 커스텀 메트릭
const errorRate = new Rate('coupon_issue_errors');
const responseTime = new Trend('coupon_issue_duration');
const successCount = new Counter('coupon_issue_success');
const soldOutCount = new Counter('coupon_sold_out');
const lockTimeoutCount = new Counter('coupon_lock_timeout');

const scenario = __ENV.SCENARIO || 'baseline';

export const options = {
  stages: SCENARIOS[scenario.toUpperCase()]?.stages || SCENARIOS.SPIKE.stages,
  thresholds: {
    ...THRESHOLDS.WRITE,
    'coupon_issue_errors': ['rate<0.5'], // 50% 이하 (선착순이므로 높은 실패율 예상)
    'coupon_issue_duration': ['p(95)<800'],
  },
};

export default function () {
  const couponId = TEST_DATA.COUPON_ID;
  const userId = randomInt(1, TEST_DATA.USER_COUNT);

  const url = `${BASE_URL}/coupons/${couponId}/issue?userId=${userId}`;

  const startTime = Date.now();
  const res = http.post(url);
  const duration = Date.now() - startTime;

  // 응답 검증
  const success = check(res, {
    'status is 200': (r) => r.status === 200,
    'has issuance data': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.data && body.data.issuanceId !== undefined;
      } catch (e) {
        return false;
      }
    },
    'response time < 1.5s': (r) => r.timings.duration < 1500,
  });

  // 에러 분류
  if (!success) {
    if (res.status === 409) {
      // 쿠폰 소진
      soldOutCount.add(1);
    } else if (res.status === 429) {
      // 락 타임아웃 (Too Many Requests)
      lockTimeoutCount.add(1);
    } else if (res.status >= 500) {
      console.error(`[CRITICAL] Server error: ${res.status}`);
    }
  } else {
    successCount.add(1);
  }

  // 메트릭 기록
  errorRate.add(!success);
  responseTime.add(duration);

  if (!success && res.status >= 500) {
    logError(res, `POST /coupons/${couponId}/issue`);
  }

  // 선착순 이벤트는 연속 클릭이므로 sleep 최소화
  sleep(0.1);
}

export function handleSummary(data) {
  const summary = [];
  summary.push('\n' + '='.repeat(60));
  summary.push('Coupon Issue (Sync) API - Load Test Summary');
  summary.push('='.repeat(60));
  summary.push('⚠️  This is a first-come-first-served event simulation');
  summary.push('    High error rate is expected due to limited coupon quantity');
  summary.push('='.repeat(60));

  const metrics = data.metrics;

  if (metrics.coupon_issue_success) {
    summary.push(`✅ Successful Issuances: ${metrics.coupon_issue_success.values.count}`);
  }

  if (metrics.coupon_sold_out) {
    summary.push(`🎫 Sold Out Errors (409): ${metrics.coupon_sold_out.values.count}`);
  }

  if (metrics.coupon_lock_timeout) {
    summary.push(`⏱️  Lock Timeout Errors (429): ${metrics.coupon_lock_timeout.values.count}`);
  }

  if (metrics.coupon_issue_errors) {
    const errorRate = (metrics.coupon_issue_errors.values.rate * 100).toFixed(2);
    summary.push(`❌ Total Error Rate: ${errorRate}%`);
  }

  if (metrics.coupon_issue_duration) {
    summary.push(`⏳ Avg Response Time: ${metrics.coupon_issue_duration.values.avg.toFixed(2)}ms`);
    summary.push(`   P95 Response Time: ${metrics.coupon_issue_duration.values['p(95)'].toFixed(2)}ms`);
    summary.push(`   P99 Response Time: ${metrics.coupon_issue_duration.values['p(99)'].toFixed(2)}ms`);
  }

  if (metrics.http_reqs) {
    summary.push(`🚀 TPS: ${metrics.http_reqs.values.rate.toFixed(2)} req/s`);
  }

  summary.push('='.repeat(60) + '\n');

  return {
    'stdout': summary.join('\n'),
  };
}
