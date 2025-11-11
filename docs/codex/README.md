# Codex Implementation Notes

본 문서는 “가장 많이 주문한 상품 랭킹”과 “선착순 쿠폰 비동기 발급” 기능을 설계 기반으로 구현한 내역을 정리한 것입니다. 실제 Kafka 등 외부 인프라는 사용하지 않고 Mock/이벤트 기반으로 동작을 시뮬레이션했습니다.

## 1. Product Ranking Pipeline

### 요구사항 정리
- 주문 확정 직후 Top-N 상품을 빠르게 노출해야 하며, Redis Sorted Set을 이용해 누적 주문 수를 관리합니다.
- 실시간/일/주/월 랭킹을 모두 유지하며, 정합성 보정을 위한 재계산 배치가 향후 추가될 수 있도록 설계합니다.
- 주문 취소/환불 시 음수 처리로 차감을 지원할 수 있도록 추상화합니다.

### 핵심 컴포넌트
| 파일 | 역할 |
| --- | --- |
| `src/main/java/.../application/service/ProductRankingUpdater.java` | `OrderCompletedEvent`를 받아 각 기간별 랭킹 키로 수량을 증가시킵니다. |
| `src/main/java/.../application/service/RankingKeyResolver.java` | REALTIME/DAILY/WEEKLY/MONTHLY 키 네이밍 규칙을 캡슐화합니다. |
| `src/main/java/.../infrastructure/ranking/RedisProductRankingAdapter.java` | Redis Sorted Set에 대한 ZINCRBY/ZREVRANGE 연산을 수행합니다. |
| `src/main/java/.../infrastructure/outbox/MockMessageProducer.java` | Outbox → “OutboundMessagePublishedEvent”로 브로커를 모킹하고 리스너에게 이벤트를 전달합니다. |
| `src/main/java/.../infrastructure/ranking/OrderCompletedRankingListener.java` | Mock 이벤트를 수신해 JSON 페이로드를 `OrderCompletedEvent`로 역직렬화하고 업데이트 로직을 호출합니다. |
| `src/main/java/.../interfaces/web/RankingController.java` | `/api/v1/rankings/products` 엔드포인트에서 Top-N를 조회합니다. |

### 흐름 요약
1. 주문 완료 시 `OrderService` → `OutboxOrderEventPublisher` → Outbox 저장.
2. `OutboxEventDispatcher`가 `MockMessageProducer`를 통해 `OutboundMessagePublishedEvent` 발행.
3. `OrderCompletedRankingListener`가 이벤트 수신 후 `ProductRankingUpdater` 호출.
4. `ProductRankingUpdater`는 기간별 키(`RankingKeyResolver`)를 계산하고 `ProductRankingPort.incrementScores`로 수량 증가.
5. 조회 시 `ProductRankingService`가 Redis에서 Top-N을 읽고, `ProductBulkReadPort`로 상품 메타를 일괄 조회하여 API 응답으로 가공.
6. Redis 장애 시 `ProductRankingService`에서 empty result로 graceful fallback.

### 운영 고려사항
- 정합성 재검증: 배치에서 `ranking:product:*` 키와 RDB 주문 집계를 비교 후 보정 가능.
- 모니터링: `RedisProductRankingAdapter`에 로그를 남기고, Outbox/Kafka consumer lag 대신 `OutboundMessagePublishedEvent` 발행 카운트를 추적할 수 있습니다.

## 2. Asynchronous Coupon Issuance

### 요구사항 정리
- 선착순 쿠폰을 Redis Lua Script로 race 없이 처리하고, 실제 DB 발급은 비동기로 수행합니다.
- 사용자 중복 발급, 재고 초과, 시스템 장애를 명확히 구분해야 합니다.
- Redis 장애 시 빠른 실패와 추후 재처리가 가능해야 합니다.

### 핵심 컴포넌트
| 파일 | 역할 |
| --- | --- |
| `src/main/java/.../application/service/AsyncCouponIssueService.java` | 비동기 발급 진입점. 쿠폰 검증 → Redis Lua → 메시지 발행 흐름을 담당합니다. |
| `src/main/java/.../infrastructure/coupon/CouponIssueLuaScriptExecutor.java` | `DECR + SISMEMBER + SADD`를 한 Lua 스크립트로 묶어 원자성을 확보합니다. |
| `src/main/java/.../infrastructure/coupon/InMemoryCouponIssueMessagePublisher.java` | Kafka 대신 Spring 이벤트로 메시지를 전달합니다. |
| `src/main/java/.../infrastructure/coupon/CouponIssueMessageListener.java` | 메시지를 비동기 처리하며 RDB 발급/Redis issued 카운터 증가를 보장합니다. |
| `src/main/java/.../infrastructure/config/RedisCounterInitializer.java` | 앱 시작 시 발급/잔여 카운터와 사용자 Set을 Redis에 초기화합니다. |
| `src/main/java/.../interfaces/web/CouponController.java` | `/coupons/{couponId}/issue-async` 엔드포인트에서 비동기 플로우를 노출합니다. |

### 흐름 요약
1. API 호출 → `AsyncCouponIssueService.request`가 쿠폰 유효성/중복 발급을 RDB 기준으로 검증.
2. Redis 키가 비어있으면 기존 발급 수/잔여 수량을 RDB에서 계산해 seed.
3. Lua Script 결과에 따라
   - `RESERVED`: UUID 기반 requestId를 생성하고 메시지를 publish, 즉시 202 Accepted 응답.
   - `SOLD_OUT`: 잔여 수량 부족.
   - `DUPLICATE`: 사용자 Set에 이미 존재.
4. `CouponIssueMessageListener`는 메시지를 소비하여 `CouponReadWritePort.issueCoupon` 호출 후 `coupon:{id}:issued` 카운터를 증가.
5. 동시성 이슈(UNIQUE 제약 위반)는 listener에서 로그로 남기고 재시도 전략 추가 시 확장 가능.

### 운영 고려사항
- `RedisCounterInitializer`가 RDB와 Redis 상태를 동기화하므로 재배포 시에도 일관성 유지.
- 향후 Dead Letter Queue, 재시도 백오프, TTL 종료 후 정리 배치를 붙이도록 확장 포인트 확보.

## 3. 테스트 및 검증

| 명령 | 목적 |
| --- | --- |
| `./gradlew test --tests 'kr.hhplus.be.server.interfaces.web.OrderControllerTest'` | 주문 API 단위 테스트. 추가된 `CompleteOrderUseCase` Mock으로 의존성 충족. |
| `./gradlew test --tests 'kr.hhplus.be.server.application.service.AsyncCouponIssueServiceTest'` | 비동기 쿠폰 서비스 단위 테스트 (예약/품절 케이스). |
| `./gradlew test` | 전체 테스트 실행 (로컬 Docker 미사용 환경에서는 Testcontainers 이슈로 실패 가능). |

> 참고: 통합 테스트(`UserFlowIntegrationTest`, `DistributedLockIntegrationTest` 등)는 Testcontainers 기반이라 로컬 환경에서 Docker 접근 권한이 없으면 실패합니다. CI 또는 Docker 사용 가능한 환경에서 재실행해야 합니다.

## 4. 모니터링 / 향후 개선
- **Ranking**: `ProductRankingService`에서 Redis 예외 감지, `ProductRankingUpdater`에서 처리량 로그 유지. 추후 Prometheus Counter/Histogram으로 확장 가능.
- **Coupon**: Lua 수행 시간, 메시지 발행/소비 건수, `CouponIssueMessageListener` 실패 횟수를 지표화. Redis TTL 관리(이벤트 종료 시)와 DLQ 연결 등을 추가로 구현 예정.
- **Failover**: Redis 장애 시 랭킹 조회는 graceful degrade, 쿠폰 발급은 즉시 실패 후 재시도 안내 메시지를 반환하도록 했습니다. Replica 또는 Sentinel 연동은 구성으로 확장 가능합니다.

---

Branch: `feat/ranking-coupon`  
Commits:
1. `feat: add product ranking pipeline`
2. `feat: support async coupon issuance`

위 커밋들에 구현이 모두 포함되어 있으며, 해당 브랜치에서 추가 개발/테스트를 진행하면 됩니다.
