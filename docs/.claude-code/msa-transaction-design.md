# MSA 전환 설계 및 분산 트랜잭션 가이드

> 이커머스 플랫폼의 MSA 전환 설계와 분산 트랜잭션 처리 방안

## 📋 목차

1. [현재 아키텍처 분석](#현재-아키텍처-분석)
2. [MSA 배포 단위 설계](#msa-배포-단위-설계)
3. [분산 트랜잭션 문제점](#분산-트랜잭션-문제점)
4. [Saga 패턴 해결 방안](#saga-패턴-해결-방안)
5. [구현 가이드](#구현-가이드)

---

## 현재 아키텍처 분석

### 모놀리식 구조

```
┌─────────────────────────────────────────────────────┐
│              Spring Boot Application                │
│                                                      │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐         │
│  │  Order   │  │  Coupon  │  │  Wallet  │         │
│  │ Service  │  │ Service  │  │ Service  │         │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘         │
│       │             │             │                 │
│       └─────────────┴─────────────┘                 │
│                     │                                │
│         ┌───────────▼───────────┐                   │
│         │   Single Database     │                   │
│         │  (MySQL with ACID)    │                   │
│         └───────────────────────┘                   │
└─────────────────────────────────────────────────────┘
```

### 주문 생성 흐름 (현재)

```java
@Transactional  // ← 단일 트랜잭션으로 원자성 보장
public Result create(Command cmd) {
    // 1. 상품 가격 조회
    var prices = pricePort.loadPrices(productIds);

    // 2. 쿠폰 검증
    var coupon = couponPort.findApplicable(userId, couponCode, subtotal);

    // 3. 재고 예약 (분산락 + Optimistic Lock)
    inventoryService.reserveWithLock(productId, qty, null);

    // 4. 지갑 차감 (Pessimistic Lock)
    walletService.debit(new Command(userId, total, idempotencyKey));

    // 5. 주문 저장
    Long orderId = orderWritePort.createReservedOrder(...);

    return new Result(orderId, "RESERVED", total);
}
```

**장점:**
- ✅ 단일 DB 트랜잭션으로 ACID 보장
- ✅ 재고 예약 실패 → 전체 롤백 (자동 복구)
- ✅ 구현 단순, 디버깅 쉬움

**한계:**
- ❌ 서비스 간 강결합 (Wallet, Inventory, Coupon)
- ❌ 스케일링 어려움 (전체를 함께 확장해야 함)
- ❌ 장애 전파 (한 서비스 장애 시 전체 영향)

---

## MSA 배포 단위 설계

### 도메인 주도 서비스 분리

#### 1. 핵심 원칙: Database per Service

각 서비스는 독립적인 데이터베이스를 소유하며, 다른 서비스의 DB에 직접 접근하지 않음

```
┌─────────────────────────────────────────────────────────────┐
│                    API Gateway / BFF                         │
└───────────────────────┬─────────────────────────────────────┘
                        │
        ┌───────────────┼────────────────┬────────────────┐
        │               │                │                │
┌───────▼────────┐  ┌───▼────────┐  ┌───▼────────┐  ┌───▼────────┐
│ Order Service  │  │  Wallet    │  │  Coupon    │  │  Product   │
│                │  │  Service   │  │  Service   │  │  Service   │
│ - 주문 생성     │  │ - 충전     │  │ - 발급     │  │ - 조회     │
│ - 주문 완료     │  │ - 차감     │  │ - 검증     │  │ - 가격     │
│ - 주문 조회     │  │ - 환불*    │  │ - 해제*    │  │            │
└───────┬────────┘  └────┬───────┘  └────┬───────┘  └────┬───────┘
        │                │                │                │
   ┌────▼────┐      ┌────▼────┐     ┌────▼────┐     ┌────▼────┐
   │Order DB │      │Wallet DB│     │Coupon DB│     │Product  │
   └─────────┘      └─────────┘     └─────────┘     │   DB    │
                                                     └─────────┘

┌─────────────────────────────────────────────────────────────┐
│              Inventory Service (재고 관리)                   │
│  - 재고 조회                                                 │
│  - 재고 예약                                                 │
│  - 재고 해제*                                                │
└───────┬─────────────────────────────────────────────────────┘
        │
   ┌────▼─────┐
   │Inventory │
   │   DB     │
   └──────────┘

         ┌─────────────────────┐
         │   Event Broker      │
         │   (Kafka/RabbitMQ)  │
         └─────────────────────┘
```

**\* 보상 트랜잭션 API**

#### 2. 서비스별 책임과 API

##### Order Service (주문 서비스)

**책임:** 주문 생성, 완료, 조회 및 Saga Orchestration

**데이터:** `orders`, `order_items`

**API:**
- `POST /orders` - 주문 생성 (Saga 시작)
- `PATCH /orders/{id}/complete` - 주문 완료
- `GET /orders/{id}` - 주문 조회
- `DELETE /orders/{id}` - 주문 취소 (Saga 보상)

**핵심 로직:**
```java
@Service
public class OrderSagaOrchestrator {
    // 주문 생성 시 각 서비스 호출 및 보상 트랜잭션 관리
    public OrderSagaResult createOrder(CreateOrderCommand cmd) {
        // Orchestration 로직
    }
}
```

##### Wallet Service (결제 서비스)

**책임:** 지갑 충전, 차감, 환불

**데이터:** `wallets`, `wallet_transactions`

**API:**
- `POST /wallets/{userId}/topup` - 충전
- `POST /wallets/{userId}/debit` - 차감 (주문 결제)
- `POST /wallets/{userId}/refund` - 환불 (보상 트랜잭션)
- `GET /wallets/{userId}/balance` - 잔액 조회

**보상 트랜잭션:**
```java
@PostMapping("/{userId}/refund")
public RefundResponse refund(@PathVariable Long userId, @RequestBody RefundRequest request) {
    return walletService.refund(new RefundWalletUseCase.Command(
        userId,
        request.amount(),
        request.originalIdempotencyKey(),
        request.reason()
    ));
}
```

##### Coupon Service (쿠폰 서비스)

**책임:** 쿠폰 발급, 검증, 사용, 해제

**데이터:** `coupons`, `coupon_issuances`

**API:**
- `POST /coupons/issue` - 쿠폰 발급
- `POST /coupons/validate` - 쿠폰 검증 (주문 시 사용)
- `POST /coupons/release` - 쿠폰 해제 (보상 트랜잭션)
- `GET /coupons/{userId}` - 보유 쿠폰 조회

##### Inventory Service (재고 서비스)

**책임:** 재고 조회, 예약, 해제

**데이터:** `inventory`, `stock_movements`

**API:**
- `POST /inventory/reserve` - 재고 예약
- `POST /inventory/release` - 재고 해제 (보상 트랜잭션)
- `GET /inventory/{productId}` - 재고 조회

##### Product Service (상품 서비스)

**책임:** 상품 조회, 가격 조회 (읽기 전용)

**데이터:** `products`

**API:**
- `GET /products` - 상품 목록
- `GET /products/{id}` - 상품 상세
- `GET /products/prices` - 가격 일괄 조회

---

## 분산 트랜잭션 문제점

### 문제 시나리오: 주문 생성 실패

```
┌──────────────────────────────────────────────────────────────┐
│  Order Service (주문 생성 요청)                               │
└──────────────┬───────────────────────────────────────────────┘
               │
  ┌────────────┼────────────┬────────────┬────────────┐
  │            │            │            │            │
  ▼            ▼            ▼            ▼            ▼
┌────────┐ ┌──────┐ ┌──────────┐ ┌────────┐ ┌──────────┐
│Product │ │Coupon│ │Inventory │ │ Wallet │ │Order DB  │
│ (가격) │ │(사용)│ │ (예약)   │ │ (차감) │ │ (저장)   │
└────────┘ └──────┘ └──────────┘ └────────┘ └──────────┘
   ✓          ✓          ✓          ✓          ✗
 성공       성공        성공        성공      실패!
```

**결과:**
- ❌ 쿠폰은 이미 사용됨
- ❌ 재고는 이미 차감됨
- ❌ 지갑 잔액은 이미 차감됨
- ❌ 주문은 저장 안 됨 → **데이터 불일치!**

### 왜 롤백이 안 되는가?

**모놀리식 (단일 DB):**
```java
@Transactional  // Spring 트랜잭션
public void createOrder() {
    couponService.use();      // 쿠폰 사용
    inventoryService.reserve(); // 재고 예약
    walletService.debit();     // 잔액 차감
    orderRepository.save();    // 주문 저장 ← 실패 시 전체 롤백
}
```
→ 하나의 트랜잭션이므로 실패 시 전부 롤백 (ACID 보장)

**MSA (분산 DB):**
```java
public void createOrder() {
    couponClient.use();        // 쿠폰 서비스 HTTP 호출 (별도 DB, 별도 트랜잭션)
    inventoryClient.reserve(); // 재고 서비스 HTTP 호출 (별도 DB, 별도 트랜잭션)
    walletClient.debit();      // 지갑 서비스 HTTP 호출 (별도 DB, 별도 트랜잭션)
    orderRepository.save();    // 주문 저장 ← 실패해도 앞의 서비스는 이미 커밋됨!
}
```
→ 각각 독립적인 트랜잭션이므로 **롤백 불가!**

---

## Saga 패턴 해결 방안

### Option 1: Orchestration Saga (중앙 제어)

#### 아키텍처

```
┌─────────────────────────────────────────────┐
│          Order Saga Orchestrator            │
│         (중앙 컨트롤러 - Order Service)       │
└─────────────────┬───────────────────────────┘
                  │
    ┌─────────────┼─────────────┬──────────────┐
    │             │             │              │
    ▼             ▼             ▼              ▼
┌───────┐    ┌────────┐    ┌────────┐    ┌──────┐
│Coupon │    │Inventory│   │ Wallet │    │Order │
│Service│    │ Service │   │Service │    │  DB  │
└───────┘    └─────────┘    └────────┘    └──────┘
```

#### 흐름

```
OrderSagaOrchestrator:

1️⃣ CouponService.reserve(userId, couponCode)
   └─ 성공 → 계속 진행
   └─ 실패 → 즉시 중단 (보상 불필요)

2️⃣ InventoryService.reserve(productId, qty, orderId)
   └─ 성공 → 계속 진행
   └─ 실패 → compensate:
       └─ CouponService.release(userId, couponCode)

3️⃣ WalletService.debit(userId, amount, idempotencyKey)
   └─ 성공 → 계속 진행
   └─ 실패 → compensate:
       ├─ InventoryService.release(productId, qty, orderId)
       └─ CouponService.release(userId, couponCode)

4️⃣ OrderRepository.save(order)
   └─ 성공 → OrderCompletedEvent 발행
   └─ 실패 → compensate:
       ├─ WalletService.refund(userId, amount)
       ├─ InventoryService.release(productId, qty, orderId)
       └─ CouponService.release(userId, couponCode)
```

#### 구현 예시

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderSagaOrchestrator {
    private final CouponServiceClient couponClient;
    private final InventoryServiceClient inventoryClient;
    private final WalletServiceClient walletClient;
    private final OrderRepository orderRepository;

    public OrderSagaResult createOrder(CreateOrderCommand cmd) {
        SagaContext context = new SagaContext();

        try {
            // Step 1: 쿠폰 예약
            log.info("[Saga] Step 1: 쿠폰 예약 시작");
            context.couponReservationId = couponClient.reserve(
                cmd.userId(), cmd.couponCode()
            );
            log.info("[Saga] Step 1: 쿠폰 예약 완료 reservationId={}", context.couponReservationId);

            // Step 2: 재고 예약
            log.info("[Saga] Step 2: 재고 예약 시작");
            context.inventoryReservationId = inventoryClient.reserve(
                cmd.productId(), cmd.qty(), cmd.idempotencyKey()
            );
            log.info("[Saga] Step 2: 재고 예약 완료 reservationId={}", context.inventoryReservationId);

            // Step 3: 지갑 차감
            log.info("[Saga] Step 3: 지갑 차감 시작");
            context.walletTransactionId = walletClient.debit(
                cmd.userId(), cmd.total(), cmd.idempotencyKey()
            );
            log.info("[Saga] Step 3: 지갑 차감 완료 txId={}", context.walletTransactionId);

            // Step 4: 주문 저장
            log.info("[Saga] Step 4: 주문 저장 시작");
            Order order = orderRepository.save(new Order(cmd));
            log.info("[Saga] Step 4: 주문 저장 완료 orderId={}", order.getId());

            log.info("[Saga] 주문 생성 성공 orderId={}", order.getId());
            return OrderSagaResult.success(order.getId());

        } catch (CouponReservationException e) {
            log.error("[Saga] Step 1 실패: 쿠폰 예약 실패 reason={}", e.getMessage());
            // 쿠폰 예약 실패 → 보상 불필요
            return OrderSagaResult.failure("쿠폰 예약 실패");

        } catch (InventoryReservationException e) {
            log.error("[Saga] Step 2 실패: 재고 예약 실패 reason={}", e.getMessage());
            // 재고 예약 실패 → 쿠폰 해제
            compensate(context, Step.COUPON);
            return OrderSagaResult.failure("재고 부족");

        } catch (WalletDebitException e) {
            log.error("[Saga] Step 3 실패: 지갑 차감 실패 reason={}", e.getMessage());
            // 지갑 차감 실패 → 재고, 쿠폰 해제
            compensate(context, Step.INVENTORY, Step.COUPON);
            return OrderSagaResult.failure("잔액 부족");

        } catch (Exception e) {
            log.error("[Saga] Step 4 실패: 주문 저장 실패 reason={}", e.getMessage());
            // 주문 저장 실패 → 전체 보상
            compensate(context, Step.WALLET, Step.INVENTORY, Step.COUPON);
            return OrderSagaResult.failure("주문 생성 실패");
        }
    }

    private void compensate(SagaContext context, Step... steps) {
        log.warn("[Saga] 보상 트랜잭션 시작 steps={}", Arrays.toString(steps));

        for (Step step : steps) {
            try {
                switch (step) {
                    case COUPON -> {
                        log.info("[Saga] 보상: 쿠폰 해제 reservationId={}", context.couponReservationId);
                        couponClient.release(context.couponReservationId);
                    }
                    case INVENTORY -> {
                        log.info("[Saga] 보상: 재고 해제 reservationId={}", context.inventoryReservationId);
                        inventoryClient.release(context.inventoryReservationId);
                    }
                    case WALLET -> {
                        log.info("[Saga] 보상: 지갑 환불 txId={}", context.walletTransactionId);
                        walletClient.refund(context.walletTransactionId);
                    }
                }
            } catch (Exception e) {
                log.error("[Saga] 보상 트랜잭션 실패: step={}, error={}", step, e.getMessage(), e);
                // Dead Letter Queue에 저장하여 수동 처리
                deadLetterQueue.send(new CompensationFailedEvent(step, context, e));
            }
        }

        log.info("[Saga] 보상 트랜잭션 완료");
    }

    @Data
    static class SagaContext {
        String couponReservationId;
        String inventoryReservationId;
        Long walletTransactionId;
    }

    enum Step {
        COUPON, INVENTORY, WALLET
    }
}
```

#### 장점

- ✅ **명확한 흐름**: 중앙에서 제어하므로 디버깅 쉬움
- ✅ **보상 관리 용이**: 한 곳에서 보상 트랜잭션 관리
- ✅ **장애 추적 간편**: Saga 로그 한 곳에 집중

#### 단점

- ❌ **중앙 집중화**: Orchestrator에 로직 집중
- ❌ **서비스 간 결합**: Orchestrator가 모든 서비스 알아야 함

---

### Option 2: Choreography Saga (이벤트 기반)

#### 아키텍처

```
Order Service                Coupon Service
     │                             │
     │──① OrderCreatedEvent────────▶│
     │                          (쿠폰 예약)
     │◀──② CouponReservedEvent─────│
     │
     ├──③ InventoryReserveRequest──▶ Inventory Service
     │◀──④ InventoryReservedEvent───│
     │
     ├──⑤ WalletDebitRequest────────▶ Wallet Service
     │◀──⑥ WalletDebitedEvent────────│
     │
     └──⑦ OrderCompletedEvent (발행)

실패 시:
     │◀──✗ WalletDebitFailedEvent───│
     │
     ├──⑧ ReleaseInventoryEvent─────▶ Inventory Service
     ├──⑨ ReleaseCouponEvent────────▶ Coupon Service
     └──⑩ OrderFailedEvent (발행)
```

#### 구현 예시

```java
// Order Service
@Service
@RequiredArgsConstructor
public class OrderEventHandler {
    private final ApplicationEventPublisher eventPublisher;
    private final OrderRepository orderRepository;

    public void createOrder(CreateOrderCommand cmd) {
        // 주문 PENDING 상태로 저장
        Order order = orderRepository.save(Order.pending(cmd));

        // 이벤트 발행 → Coupon Service가 구독
        eventPublisher.publishEvent(new OrderCreatedEvent(order));
    }

    @EventListener
    public void onCouponReserved(CouponReservedEvent event) {
        // 쿠폰 예약 성공 → 재고 예약 요청
        eventPublisher.publishEvent(new ReserveInventoryEvent(event.orderId()));
    }

    @EventListener
    public void onInventoryReserved(InventoryReservedEvent event) {
        // 재고 예약 성공 → 지갑 차감 요청
        eventPublisher.publishEvent(new DebitWalletEvent(event.orderId()));
    }

    @EventListener
    public void onWalletDebited(WalletDebitedEvent event) {
        // 지갑 차감 성공 → 주문 완료
        orderRepository.markCompleted(event.orderId());
        eventPublisher.publishEvent(new OrderCompletedEvent(event.orderId()));
    }

    @EventListener
    public void onWalletDebitFailed(WalletDebitFailedEvent event) {
        // 지갑 차감 실패 → 보상 트랜잭션
        eventPublisher.publishEvent(new ReleaseInventoryEvent(event.orderId()));
        eventPublisher.publishEvent(new ReleaseCouponEvent(event.orderId()));
        orderRepository.markFailed(event.orderId());
    }
}

// Coupon Service
@Service
@RequiredArgsConstructor
public class CouponEventHandler {
    @EventListener
    public void onOrderCreated(OrderCreatedEvent event) {
        try {
            reserveCoupon(event.userId(), event.couponCode());
            eventPublisher.publishEvent(new CouponReservedEvent(event.orderId()));
        } catch (Exception e) {
            eventPublisher.publishEvent(new CouponReservationFailedEvent(event.orderId()));
        }
    }

    @EventListener
    public void onReleaseCoupon(ReleaseCouponEvent event) {
        releaseCoupon(event.orderId());
    }
}
```

#### 장점

- ✅ **낮은 결합도**: 서비스가 이벤트만 구독
- ✅ **높은 확장성**: 새 서비스 추가 용이

#### 단점

- ❌ **복잡한 흐름**: 이벤트가 여러 서비스에 분산
- ❌ **디버깅 어려움**: 로그가 여러 서비스에 흩어짐

---

### 비교: Orchestration vs Choreography

| 항목 | Orchestration | Choreography |
|------|--------------|--------------|
| **제어 방식** | 중앙 Orchestrator | 각 서비스 독립적 |
| **복잡도** | 집중된 로직 (이해 쉬움) | 분산된 로직 (이해 어려움) |
| **장애 추적** | 쉬움 (한 곳에서 추적) | 어려움 (여러 서비스 추적) |
| **결합도** | 높음 (Orchestrator 의존) | 낮음 (이벤트만 의존) |
| **확장성** | 중간 | 높음 |
| **보상 트랜잭션** | 명시적 (코드로 관리) | 이벤트 기반 (복잡) |
| **적합한 경우** | 복잡한 비즈니스 로직 | 단순한 워크플로우 |

**권장:** 주문 생성처럼 복잡한 비즈니스는 **Orchestration Saga** 사용

---

## 구현 가이드

### 1단계: 보상 트랜잭션 API 준비 ✅

각 서비스에 보상 트랜잭션 API 구현:

```java
// ✅ 이미 구현됨
CouponService.release(ReleaseCouponUseCase.Command)
InventoryService.release(ReleaseInventoryUseCase.Command)
WalletService.refund(RefundWalletUseCase.Command)
```

### 2단계: Saga State 테이블 생성

```sql
CREATE TABLE saga_state (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    saga_type VARCHAR(50) NOT NULL,  -- 'ORDER_CREATION'
    saga_id VARCHAR(100) NOT NULL,   -- idempotencyKey
    status VARCHAR(20) NOT NULL,     -- 'PENDING', 'COMPLETED', 'FAILED'
    current_step VARCHAR(50),        -- 'COUPON_RESERVED', 'INVENTORY_RESERVED'
    payload JSON,                    -- 주문 정보
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_saga_type_id (saga_type, saga_id)
);

CREATE TABLE saga_step (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    saga_state_id BIGINT NOT NULL,
    step_name VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,     -- 'PENDING', 'COMPLETED', 'FAILED', 'COMPENSATED'
    request JSON,                    -- 요청 데이터
    response JSON,                   -- 응답 데이터
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (saga_state_id) REFERENCES saga_state(id)
);
```

### 3단계: Service Client 구현

```java
@Component
@RequiredArgsConstructor
public class WalletServiceClient {
    private final RestTemplate restTemplate;
    private final String walletServiceUrl;

    public Long debit(Long userId, Long amount, String idempotencyKey) {
        DebitRequest request = new DebitRequest(userId, amount, idempotencyKey);

        ResponseEntity<DebitResponse> response = restTemplate.postForEntity(
            walletServiceUrl + "/wallets/" + userId + "/debit",
            request,
            DebitResponse.class
        );

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new WalletDebitException("지갑 차감 실패: " + response.getStatusCode());
        }

        return response.getBody().transactionId();
    }

    public void refund(Long transactionId) {
        restTemplate.postForEntity(
            walletServiceUrl + "/wallets/refund",
            new RefundRequest(transactionId),
            Void.class
        );
    }
}
```

### 4단계: Circuit Breaker 추가

```java
@CircuitBreaker(name = "walletService", fallbackMethod = "debitFallback")
public Long debit(Long userId, Long amount, String idempotencyKey) {
    // ...
}

private Long debitFallback(Long userId, Long amount, String idempotencyKey, Throwable t) {
    log.error("[WalletClient] Circuit breaker 열림: userId={}, error={}", userId, t.getMessage());
    throw new WalletServiceUnavailableException("지갑 서비스 일시적 장애");
}
```

```yaml
resilience4j:
  circuitbreaker:
    instances:
      walletService:
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        sliding-window-size: 10
```

### 5단계: MSA 인프라 구성

```yaml
# docker-compose.yml
version: '3.8'
services:
  order-service:
    build: ./order-service
    ports:
      - "8081:8080"
    environment:
      - SPRING_DATASOURCE_URL=jdbc:mysql://order-db:3306/order
    depends_on:
      - order-db
      - kafka

  wallet-service:
    build: ./wallet-service
    ports:
      - "8082:8080"
    environment:
      - SPRING_DATASOURCE_URL=jdbc:mysql://wallet-db:3306/wallet
    depends_on:
      - wallet-db
      - kafka

  coupon-service:
    build: ./coupon-service
    ports:
      - "8083:8080"
    environment:
      - SPRING_DATASOURCE_URL=jdbc:mysql://coupon-db:3306/coupon
    depends_on:
      - coupon-db
      - kafka

  inventory-service:
    build: ./inventory-service
    ports:
      - "8084:8080"
    environment:
      - SPRING_DATASOURCE_URL=jdbc:mysql://inventory-db:3306/inventory
    depends_on:
      - inventory-db
      - kafka

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    ports:
      - "9092:9092"
    environment:
      - KAFKA_ZOOKEEPER_CONNECT=zookeeper:2181

  order-db:
    image: mysql:8.0
    environment:
      - MYSQL_DATABASE=order
      - MYSQL_ROOT_PASSWORD=root

  wallet-db:
    image: mysql:8.0
    environment:
      - MYSQL_DATABASE=wallet
      - MYSQL_ROOT_PASSWORD=root

  coupon-db:
    image: mysql:8.0
    environment:
      - MYSQL_DATABASE=coupon
      - MYSQL_ROOT_PASSWORD=root

  inventory-db:
    image: mysql:8.0
    environment:
      - MYSQL_DATABASE=inventory
      - MYSQL_ROOT_PASSWORD=root
```

---

## 요약

### 현재 상태 (모놀리식)
- ✅ 단일 트랜잭션으로 ACID 보장
- ❌ 서비스 간 강결합, 스케일링 어려움

### MSA 전환 후
- ✅ 서비스 독립 배포, 스케일링 유연
- ❌ 분산 트랜잭션 문제 발생

### Saga 패턴으로 해결
- ✅ **Orchestration**: 중앙 제어, 명확한 흐름 (권장)
- ✅ **Choreography**: 이벤트 기반, 낮은 결합도

### 구현 완료 항목
- ✅ 보상 트랜잭션 API (`release`, `refund`)
- ✅ Application Event 분리 (트랜잭션 독립)

### 다음 단계
1. Saga Orchestrator 구현
2. Service Client & Circuit Breaker
3. Saga State 관리
4. MSA 인프라 구성
