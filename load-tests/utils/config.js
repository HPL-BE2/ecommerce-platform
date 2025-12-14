// 공통 설정
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 테스트 데이터 범위
export const TEST_DATA = {
  USER_COUNT: 100,      // 테스트용 사용자 수 (1~100)
  PRODUCT_COUNT: 20,    // 테스트용 상품 수 (1~20)
  COUPON_ID: 1,         // 테스트용 쿠폰 ID
};

// 기본 헤더
export const HEADERS = {
  'Content-Type': 'application/json',
};

// 성능 임계값
export const THRESHOLDS = {
  // 읽기 API
  READ: {
    'http_req_duration': ['p(95)<500', 'p(99)<1000'],
    'http_req_failed': ['rate<0.001'],
  },

  // 쓰기 API
  WRITE: {
    'http_req_duration': ['p(95)<1000', 'p(99)<2000'],
    'http_req_failed': ['rate<0.01'],
  },

  // 복잡한 트랜잭션 (주문 생성)
  COMPLEX: {
    'http_req_duration': ['p(95)<2000', 'p(99)<5000'],
    'http_req_failed': ['rate<0.05'],
  },
};

// 부하 시나리오 프로파일
export const SCENARIOS = {
  SMOKE: {
    vus: 1,
    duration: '1m',
  },

  BASELINE: {
    stages: [
      { duration: '30s', target: 100 },
      { duration: '5m', target: 100 },
      { duration: '30s', target: 0 },
    ],
  },

  PEAK: {
    stages: [
      { duration: '5m', target: 1000 },
      { duration: '10m', target: 1000 },
      { duration: '5m', target: 0 },
    ],
  },

  STRESS: {
    stages: [
      { duration: '3m', target: 500 },
      { duration: '3m', target: 1000 },
      { duration: '3m', target: 1500 },
      { duration: '3m', target: 2000 },
      { duration: '3m', target: 0 },
    ],
  },

  SPIKE: {
    stages: [
      { duration: '1m', target: 100 },
      { duration: '30s', target: 2000 },  // Spike
      { duration: '2m', target: 2000 },
      { duration: '30s', target: 100 },
      { duration: '1m', target: 0 },
    ],
  },
};
