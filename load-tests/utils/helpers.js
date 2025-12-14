/**
 * 랜덤 정수 생성
 * @param {number} min - 최소값 (포함)
 * @param {number} max - 최대값 (포함)
 * @returns {number}
 */
export function randomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

/**
 * 배열에서 랜덤 요소 선택
 * @param {Array} array
 * @returns {*}
 */
export function randomItem(array) {
  return array[Math.floor(Math.random() * array.length)];
}

/**
 * 고유한 Idempotency Key 생성
 * @param {string} prefix
 * @returns {string}
 */
export function generateIdempotencyKey(prefix = 'load-test') {
  return `${prefix}-${Date.now()}-${__VU}-${__ITER}`;
}

/**
 * Think Time 시뮬레이션 (사람처럼 대기)
 * @param {number} min - 최소 초
 * @param {number} max - 최대 초
 * @returns {number}
 */
export function randomSleep(min = 1, max = 3) {
  return Math.random() * (max - min) + min;
}

/**
 * 성공률 계산
 * @param {number} successCount
 * @param {number} totalCount
 * @returns {number} 백분율 (0~100)
 */
export function calculateSuccessRate(successCount, totalCount) {
  return totalCount === 0 ? 0 : (successCount / totalCount) * 100;
}

/**
 * HTTP 에러 로깅
 * @param {object} response - k6 HTTP response
 * @param {string} endpoint - API endpoint
 */
export function logError(response, endpoint) {
  if (response.status >= 400) {
    console.error(`[ERROR] ${endpoint} - Status: ${response.status}, Body: ${response.body.substring(0, 200)}`);
  }
}

/**
 * 결과 요약 출력
 * @param {object} summary - 커스텀 메트릭 요약
 */
export function printSummary(summary) {
  console.log('='.repeat(60));
  console.log('Test Summary');
  console.log('='.repeat(60));
  Object.entries(summary).forEach(([key, value]) => {
    console.log(`${key}: ${value}`);
  });
  console.log('='.repeat(60));
}
