# E-커머스 플랫폼

> 항해플러스 백엔드 E-커머스 상품 주문 서비스

## 목차

- [프로젝트 개요](#프로젝트-개요)
- [기술 스택](#기술-스택)
- [아키텍처](#아키텍처)
- [주요 기능](#주요-기능)
- [개발 브랜치 현황](#개발-브랜치-현황)
- [동시성 제어](#동시성-제어)
- [Getting Started](#getting-started)
- [API 문서](#api-문서)
- [문서](#문서)

---

## 프로젝트 개요

상품 주문부터 결제까지의 전체 프로세스를 처리하는 E-커머스 백엔드 시스템입니다.
대규모 트래픽 환경에서도 안정적으로 동작하도록 **동시성 제어**, **멱등성 보장**, **이벤트 기반 아키텍처**를 적용했습니다.

### 핵심 목표
- ✅ 대용량 트래픽 환경에서의 데이터 정합성 보장
- ✅ 재고 감소, 잔액 차감, 쿠폰 발급의 동시성 제어
- ✅ Clean Architecture 기반 유지보수 가능한 코드 구조
- ✅ 테스트 주도 개발 (TDD) 및 통합 테스트

---

## 기술 스택

### Backend
- **Framework**: Spring Boot 3.4.1
- **Language**: Java 17
- **Build Tool**: Gradle 8.x

### Database
- **RDBMS**: MySQL 8.0
- **ORM**: Spring Data JPA
- **Cache**: Redis 7.2

### Architecture & Design
- **Hexagonal Architecture** (Ports & Adapters)
- **Domain-Driven Design** (DDD)
- **Event-Driven Architecture**

### Testing
- **JUnit 5** + **Testcontainers**
- **멀티스레드 동시성 테스트**

### DevOps
- **Containerization**: Docker & Docker Compose
- **API Documentation**: SpringDoc OpenAPI (Swagger)

---

## 아키텍처

### Hexagonal Architecture (포트와 어댑터)

```
┌─────────────────────────────────────────────────────────────┐
│                          Presentation                        │
│                    (Controller, REST API)                    │
└────────────────────────────┬────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────┐
│                        Application                           │
│              (Use Cases, Service, Commands)                  │
└────────────────────────────┬────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────┐
│                          Domain                              │
│         (Entities, Value Objects, Domain Logic)              │
└────────────────────────────┬────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────┐
│                      Infrastructure                          │
│       (JPA Repository, Redis, External Services)             │
└─────────────────────────────────────────────────────────────┘
```

### 핵심 도메인 모델

```
┌─────────────────────────────────────────────────────────────┐
│                    E-커머스 도메인 모델                          │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  [Product] ────── [Inventory] ────── [StockMovement]       │
│      │                                     (재고 이력)       │
│      │                                                      │
│      └──── [OrderItem] ────── [Order] ────── [User]        │
│                                   │             │          │
│                                   │             │          │
│                          [CouponIssuance] ── [Coupon]      │
│                                   │                        │
│                                   │                        │
│                              [Wallet] ── [WalletTransaction]│
│                                             (잔액 이력)      │
└─────────────────────────────────────────────────────────────┘
```

---

## 주요 기능

### 1. 상품 관리
- 상품 목록 조회 (페이징, 필터링)
- 상품 상세 조회
- 재고 실시간 확인

### 2. 주문 처리
- 주문 생성 (상품 여러 개 동시 주문 가능)
- 주문 상태 관리 (`RESERVED` → `COMPLETED`)
- 쿠폰 적용 및 할인 계산
- 멱등성 보장 (`Idempotency-Key`)

### 3. 결제 시스템
- 지갑 충전 (`Topup`)
- 잔액 차감 (`Debit`) - **Pessimistic Lock**
- 거래 이력 관리
- 잔액 부족 시 주문 실패 처리

### 4. 쿠폰 시스템
- 선착순 쿠폰 발급 - **Redis Atomic Counter**
- 중복 발급 방지 (`UNIQUE` 제약조건)
- 쿠폰 적용 및 할인 계산

### 5. 동시성 제어
- **재고 감소**: Optimistic Lock (`@Version`)
- **잔액 차감**: Pessimistic Lock (`SELECT ... FOR UPDATE`)
- **쿠폰 발급**: DB UNIQUE 제약 + Redis Counter

---

## 개발 브랜치 현황

### 메인 브랜치
- **`main`**: 프로덕션 배포 브랜치 (안정 버전)

### 작업 브랜치 (Pull Request)

#### 1. `claude/concurrency-control-011CUcBMZZdREcBdSHFzyfiK`
**동시성 제어 완전 구현 브랜치** (PR 대기 중)

**커밋 내역:**
- `14f0ee1` - docs: 동시성 제어 테스트 결과 문서 작성
- `dfa4edc` - test: 멀티스레드 동시성 제어 통합 테스트 작성
- `c71fee7` - feat: Phase 3 - 쿠폰 발급 동시성 제어 구현 (DB + UNIQUE 제약)
- `c52f7e0` - feat: Phase 2 - 잔액 차감 동시성 제어 구현 (Pessimistic Lock)
- `d2f66bf` - feat: Phase 1 - 재고 감소 동시성 제어 개선 (Optimistic Lock)

**주요 변경사항:**
- ✅ 재고 감소 동시성 제어 (Pessimistic → Optimistic Lock 전환)
- ✅ 잔액 차감 동시성 제어 (Pessimistic Lock 신규 구현)
- ✅ 쿠폰 발급 동시성 제어 (Redis + DB UNIQUE)
- ✅ 멀티스레드 통합 테스트 (100명 동시 요청 시나리오)
- ✅ 동시성 테스트 결과 문서화

#### 2. `feat/#1` - 2주차 과제
**문서 작성 브랜치**
- ERD 설계
- API 명세서
- 인프라 구성도

#### 3. `feat/#3` - 상품/조회 API 개발
**상품 도메인 기본 기능**
- 상품 목록 조회 API
- 상품 상세 조회 API
- 재고 확인 기능

#### 4. `feat/#6` - 주문 API 개발
**주문 도메인 핵심 기능**
- 주문 생성 API (`POST /api/v1/orders`)
- 주문 완료 API (`POST /api/v1/orders/{orderId}/complete`)
- 멱등성 보장 (`Idempotency-Key`)

#### 5. `feat/#10` - 데이터베이스1 과제
**데이터베이스 설계 및 최적화**
- 인덱스 설계
- 쿼리 최적화
- N+1 문제 해결

---

## 동시성 제어

### 개요
대규모 트래픽 환경에서 발생할 수 있는 **Race Condition**을 방지하기 위해,
각 비즈니스 로직의 특성에 맞는 동시성 제어 기법을 적용했습니다.

### 구현 전략

| 기능 | 동시성 제어 기법 | 선택 이유 |
|------|-----------------|-----------|
| **재고 감소** | Optimistic Lock (`@Version`) | 재고 조회는 빈번하지만 구매는 상대적으로 드물어 성능 우선 |
| **잔액 차감** | Pessimistic Lock (`FOR UPDATE`) | 결제는 절대 실패하면 안 되므로 정합성 최우선 |
| **쿠폰 발급** | Redis Atomic Counter + DB UNIQUE | 선착순 이벤트는 초당 수천 건 요청이므로 고성능 필요 |

### Phase 1: 재고 감소 동시성 제어

**문제 상황**
```
[시나리오] 재고 1개 남은 상품을 2명이 동시에 주문

Thread A                        Thread B                     Inventory
─────────────────────────────────────────────────────────────────────
SELECT stock = 1                SELECT stock = 1             stock = 1
CHECK: 1 >= 1 ✓                 CHECK: 1 >= 1 ✓
UPDATE stock = 0                UPDATE stock = -1 ⚠️          stock = -1

결과: 재고 음수 발생! (Over-sell)
```

**해결 방법: Optimistic Lock**
```java
@Entity
@Table(name = "inventory")
public class InventoryEntity {
    @Id
    private Long productId;

    @Column(nullable = false)
    private Integer stock;

    @Version  // Optimistic Lock
    private Long version;
}
```

**장점:**
- Lock 대기 없어 성능 우수
- Deadlock 발생 가능성 제거
- 동시 접속자 증가 시에도 성능 유지

**테스트 결과:**
- 100명이 동시에 재고 1개 상품 주문 → **정확히 1명만 성공**
- 충돌 시 `OptimisticLockException` 발생 → 재시도 또는 실패 처리

### Phase 2: 잔액 차감 동시성 제어

**문제 상황**
```
[시나리오] 잔액 1000원인 유저가 동시에 1000원 결제 2번 시도

Thread A                        Thread B                     Wallet
─────────────────────────────────────────────────────────────────────
SELECT balance = 1000           SELECT balance = 1000        balance = 1000
CHECK: 1000 >= 1000 ✓           CHECK: 1000 >= 1000 ✓
UPDATE balance = 0              UPDATE balance = -1000 ⚠️     balance = -1000

결과: 잔액 음수 발생! (Negative Balance)
```

**해결 방법: Pessimistic Lock**
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT w FROM WalletEntity w WHERE w.userId = :userId")
Optional<WalletEntity> lockByUserId(@Param("userId") Long userId);
```

**트랜잭션 순서:**
1. 잔액 Lock 획득 (`SELECT ... FOR UPDATE`)
2. 잔액 검증 (`balance >= amount`)
3. 잔액 차감 (`balance = balance - amount`)
4. Transaction 기록
5. Commit

**테스트 결과:**
- 잔액 1000원 유저가 동시에 1000원 결제 2번 시도 → **정확히 1번만 성공**
- 두 번째 요청은 Lock 대기 후 잔액 부족으로 실패

### Phase 3: 쿠폰 발급 동시성 제어

**문제 상황**
```
[시나리오] 선착순 100명 쿠폰에 1000명이 동시 요청

Thread A                        Thread B                     Coupon
─────────────────────────────────────────────────────────────────────
SELECT COUNT(*) = 99            SELECT COUNT(*) = 99         issued = 99
CHECK: 99 < 100 ✓               CHECK: 99 < 100 ✓
INSERT (count = 100)            INSERT (count = 101) ⚠️       issued = 101

결과: 초과 발급! (Over-issuance)
```

**해결 방법: DB UNIQUE 제약 + Redis Atomic Counter**
```sql
-- DB UNIQUE 제약으로 중복 발급 방지
CREATE TABLE coupon_issuances (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  coupon_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  UNIQUE KEY uq_issue (coupon_id, user_id)  -- 중복 발급 방지
);
```

```java
// Redis Atomic Counter로 빠른 선착순 체크
Long count = redisTemplate.opsForValue().increment("coupon:" + couponId + ":count");

if (count <= maxCount) {
    couponIssuanceJpa.save(new CouponIssuanceEntity(...));
} else {
    redisTemplate.opsForValue().decrement("coupon:" + couponId + ":count");
    throw new CouponExhaustedException("쿠폰이 모두 소진되었습니다.");
}
```

**장점:**
- Redis INCR 명령은 Atomic 연산이므로 Race Condition 없음
- DB Lock 없이 초고속 처리 가능
- DB UNIQUE 제약으로 최종 안전장치 제공

**테스트 결과:**
- 선착순 100명 쿠폰에 1000명 동시 요청 → **정확히 100명만 발급**
- Redis Counter와 DB 발급 수 일치 확인

### 테스트 문서
상세한 테스트 시나리오 및 결과는 [동시성 제어 설계 문서](./docs/claude-code/concurrency-control-design.md)를 참조하세요.

---

## Getting Started

### Prerequisites

#### 1. Docker 설치
```bash
# Docker 및 Docker Compose 설치 확인
docker --version
docker-compose --version
```

#### 2. 인프라 컨테이너 실행
`local` profile로 실행하기 위해 MySQL, Redis가 설정된 Docker 컨테이너를 실행합니다.

```bash
docker-compose up -d
```

**실행 확인:**
```bash
docker-compose ps

# 예상 출력:
#   mysql    - 3306:3306
#   redis    - 6379:6379
```

### 애플리케이션 실행

#### 1. 빌드
```bash
./gradlew clean build
```

#### 2. 실행
```bash
./gradlew bootRun
```

#### 3. 실행 확인
```bash
curl http://localhost:8080/actuator/health

# 응답:
# {"status":"UP"}
```

### 데이터 초기화

애플리케이션 시작 시 `src/main/resources/data.sql`이 자동 실행되어 초기 데이터가 생성됩니다:
- 상품 10개
- 사용자 5명
- 쿠폰 3개

---

## API 문서

### Swagger UI
애플리케이션 실행 후 다음 URL에서 API 문서를 확인할 수 있습니다:

```
http://localhost:8080/swagger-ui/index.html
```

### 주요 API 엔드포인트

#### 상품 API
- `GET /api/v1/products` - 상품 목록 조회
- `GET /api/v1/products/{productId}` - 상품 상세 조회

#### 지갑 API
- `POST /api/v1/wallets/{userId}/topups` - 지갑 충전
  - Header: `Idempotency-Key` (멱등성 보장)

#### 주문 API
- `POST /api/v1/orders` - 주문 생성
  - Header: `Idempotency-Key` (멱등성 보장)
- `POST /api/v1/orders/{orderId}/complete` - 주문 완료

### 사용자 플로우 (현재 구현 API 기준)

1. **상품 탐색**: `/api/v1/products` 목록/검색과 `/api/v1/products/{productId}` 상세 조회로 구매할 상품과 재고를 확인합니다.
2. **지갑 충전**: `/api/v1/wallets/{userId}/topups` 로 사용자의 지갑 잔액을 충전하고, `Idempotency-Key` 로 멱등성을 보장합니다.
3. **주문 생성**: `/api/v1/orders` 에 상품 목록, 쿠폰, 예상 결제 금액, `Idempotency-Key` 를 전달해 주문을 `RESERVED` 상태로 생성합니다.
4. **주문 완료**: 결제 완료 후 `/api/v1/orders/{orderId}/complete` 를 호출하여 주문을 확정하고 완료 이벤트를 발행합니다.

---

## 문서

### 설계 문서
- [ERD (Entity-Relationship Diagram)](./docs/ERD.md)
- [API 명세서](./docs/API%20명세서.pdf)
- [인프라 구성도](./docs/인프라_구성도.md)
- [동시성 제어 설계 문서](./docs/claude-code/concurrency-control-design.md)

### 과제 문서
- 3주차 과제: 동시성 제어
- 데이터베이스 1 과제: 인덱스 설계 및 쿼리 최적화

---

## 프로젝트 구조

```
ecommerce-platform/
├── src/
│   ├── main/
│   │   ├── java/kr/hhplus/be/server/
│   │   │   ├── domain/          # 도메인 계층
│   │   │   │   ├── product/
│   │   │   │   ├── order/
│   │   │   │   ├── wallet/
│   │   │   │   └── coupon/
│   │   │   ├── application/     # 애플리케이션 계층 (Use Cases)
│   │   │   ├── interfaces/      # 프레젠테이션 계층 (Controllers)
│   │   │   └── infrastructure/  # 인프라 계층 (Repositories, External)
│   │   └── resources/
│   │       ├── application.yml
│   │       └── data.sql         # 초기 데이터
│   └── test/                    # 테스트 코드
├── docs/                        # 문서
├── docker-compose.yml           # 인프라 설정
└── build.gradle.kts
```

---

## 기술적 챌린지

### 1. 대규모 트래픽 처리
- Redis 캐싱 전략
- Connection Pool 튜닝
- 쿼리 최적화

### 2. 데이터 정합성
- 트랜잭션 격리 수준 설정
- 동시성 제어 기법 적용
- 보상 트랜잭션 (Saga Pattern)

### 3. 테스트 전략
- 단위 테스트 (Unit Test)
- 통합 테스트 (Integration Test with Testcontainers)
- 멀티스레드 동시성 테스트

---

## 향후 계획

- [ ] 주문 취소 및 환불 기능
- [ ] 실시간 재고 알림 (WebSocket)
- [ ] 배송 추적 시스템
- [ ] 상품 리뷰 및 평점
- [ ] 추천 시스템 (협업 필터링)
- [ ] 성능 모니터링 (Prometheus + Grafana)

---

## 라이선스

이 프로젝트는 학습 목적으로 제작되었습니다.

---

## 작성자

항해플러스 백엔드 과정

**Last Updated**: 2025-11-01
