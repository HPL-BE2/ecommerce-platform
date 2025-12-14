# Load Tests

이 디렉토리는 E-Commerce Platform의 부하 테스트 스크립트를 포함합니다.

## 📂 구조

```
load-tests/
├── scenarios/              # 테스트 시나리오 스크립트
│   ├── 01-product-list.js           # 상품 목록 조회
│   ├── 02-product-detail.js         # 상품 상세 조회
│   ├── 03-wallet-topup.js           # 지갑 충전
│   ├── 04-order-create.js           # 주문 생성
│   ├── 05-coupon-issue-sync.js      # 쿠폰 발급 (동기)
│   └── 06-full-user-journey.js      # 전체 사용자 여정
├── utils/                  # 공통 유틸리티
│   ├── config.js                    # 설정 및 상수
│   └── helpers.js                   # 헬퍼 함수
└── results/                # 테스트 결과 저장 (git ignore)
```

## 🚀 빠른 시작

### 1. k6 설치

```bash
# macOS
brew install k6

# Docker
docker pull grafana/k6
```

### 2. 애플리케이션 실행

```bash
# 인프라 실행
docker-compose up -d

# 애플리케이션 실행
./gradlew bootRun
```

### 3. 테스트 실행

#### 단일 시나리오 실행

```bash
# 상품 목록 조회 (Baseline)
k6 run load-tests/scenarios/01-product-list.js

# 주문 생성 (Stress)
k6 run --env SCENARIO=stress load-tests/scenarios/04-order-create.js

# 쿠폰 발급 (Spike)
k6 run --env SCENARIO=spike load-tests/scenarios/05-coupon-issue-sync.js
```

#### Docker로 실행

```bash
docker run --rm -i \
  --network="host" \
  -v $(pwd):/scripts \
  grafana/k6 run /scripts/load-tests/scenarios/01-product-list.js
```

#### 결과 저장

```bash
# JSON 저장
k6 run --out json=results/product-list.json load-tests/scenarios/01-product-list.js

# CSV 저장
k6 run --out csv=results/product-list.csv load-tests/scenarios/01-product-list.js
```

## 📊 시나리오별 설명

### 01. 상품 목록 조회

- **목적**: 읽기 성능 기준선, 캐시 효과 검증
- **예상 TPS**: 3000~5000 req/s
- **Target P95**: < 300ms

```bash
k6 run load-tests/scenarios/01-product-list.js
```

### 02. 상품 상세 조회

- **목적**: 캐시 히트율 측정, Hot Item 성능
- **예상 캐시 히트율**: > 80%
- **Target P95**: < 150ms

```bash
k6 run load-tests/scenarios/02-product-detail.js
```

### 03. 지갑 충전

- **목적**: 비관적 락 성능, 멱등성 검증
- **예상 TPS**: 500~1000 req/s
- **Target P95**: < 500ms

```bash
k6 run load-tests/scenarios/03-wallet-topup.js
```

### 04. 주문 생성 ⭐ (가장 중요)

- **목적**: 분산 락 경합, 동시성 제어, Breaking Point
- **예상 TPS**: 300~500 req/s
- **Target P95**: < 2000ms

```bash
# Baseline
k6 run load-tests/scenarios/04-order-create.js

# Stress
k6 run --env SCENARIO=stress load-tests/scenarios/04-order-create.js
```

### 05. 쿠폰 발급 (동기)

- **목적**: 선착순 이벤트, Thundering Herd 대응
- **예상 성공률**: 10~30% (쿠폰 한도 제한)
- **Target P95**: < 800ms

```bash
# Spike (권장)
k6 run --env SCENARIO=spike load-tests/scenarios/05-coupon-issue-sync.js
```

### 06. 전체 사용자 여정

- **목적**: End-to-End 성능, 실제 사용자 시뮬레이션
- **예상 여정 시간**: 5~10초
- **Target 성공률**: > 90%

```bash
k6 run load-tests/scenarios/06-full-user-journey.js
```

## ⚙️ 환경 변수

| 변수 | 설명 | 기본값 |
|------|------|--------|
| `BASE_URL` | API 서버 주소 | `http://localhost:8080` |
| `SCENARIO` | 시나리오 프로파일 | `baseline` |

**사용 예:**

```bash
k6 run --env BASE_URL=http://api.example.com \
       --env SCENARIO=peak \
       load-tests/scenarios/04-order-create.js
```

## 📈 시나리오 프로파일

| 프로파일 | VUs | Duration | 용도 |
|---------|-----|----------|------|
| `smoke` | 1 | 1m | Smoke Test |
| `baseline` | 100 | 6m | 평시 트래픽 |
| `peak` | 1000 | 20m | 피크 타임 |
| `stress` | 500~2000 | 15m | 한계점 탐색 |
| `spike` | 100→2000→100 | 5m | DDoS 시뮬레이션 |

## 🎯 성능 목표 (SLA)

| 지표 | 목표 |
|------|------|
| **읽기 API P95** | < 500ms |
| **쓰기 API P95** | < 1000ms |
| **주문 생성 P95** | < 2000ms |
| **에러율** | < 0.1% (읽기), < 1% (쓰기) |
| **TPS** | > 1000 req/s (전체) |

## 📝 결과 분석

테스트 후 주요 메트릭:

- `http_req_duration`: 응답 시간 (P50, P95, P99)
- `http_req_failed`: 에러율
- `http_reqs`: 총 요청 수 및 TPS
- `vus`: 가상 사용자 수
- Custom Metrics: 각 시나리오별 특화 메트릭

## 🐛 트러블슈팅

### Connection Refused

```bash
# 서버 확인
curl http://localhost:8080/actuator/health

# Docker 네트워크 확인
docker ps
```

### Too Many Open Files

```bash
# macOS/Linux
ulimit -n 65535
```

### 높은 에러율

- 테스트 데이터 확인 (DB에 사용자, 상품, 재고 존재 여부)
- 로그 확인: `docker logs ecommerce-platform-app-1`
- Redis/Kafka 상태 확인

## 📚 참고 문서

- [부하 테스트 계획서](../docs/load-testing/부하테스트_계획서.md)
- [k6 기술 문서](../docs/load-testing/k6_기술문서.md)
- [k6 공식 문서](https://k6.io/docs/)
