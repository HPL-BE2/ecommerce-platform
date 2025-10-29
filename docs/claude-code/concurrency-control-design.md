# 동시성 제어 설계 문서

> E-커머스 상품 주문 서비스의 동시성 이슈 분석 및 해결 전략

## 📋 목차

1. [현재 시스템 아키텍처](#1-현재-시스템-아키텍처)
2. [식별된 동시성 이슈](#2-식별된-동시성-이슈)
3. [구현 대상 선정](#3-구현-대상-선정)
4. [해결 전략](#4-해결-전략)
5. [구현 계획](#5-구현-계획)

---

## 1. 현재 시스템 아키텍처

### 1.1 기술 스택

- **Framework**: Spring Boot 3.4.1 + Java 17
- **Architecture**: Hexagonal Architecture (Ports & Adapters)
- **Database**: MySQL 8.0 + Spring Data JPA
- **Cache**: Redis 7.2
- **Testing**: JUnit 5 + Testcontainers

### 1.2 핵심 엔티티

```
┌─────────────────────────────────────────────────────────────────┐
│                          E-커머스 도메인 모델                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  [Product] ────── [Inventory] ────── [StockMovement]           │
│      │                                     (재고 이력)           │
│      │                                                          │
│      └──── [OrderItem] ────── [Order] ────── [User]            │
│                                   │             │              │
│                                   │             │              │
│                          [CouponIssuance] ── [Coupon]          │
│                                   │                            │
│                                   │                            │
│                              [Wallet] ── [WalletTransaction]   │
│                                             (잔액 이력)          │
└─────────────────────────────────────────────────────────────────┘
```

### 1.3 주요 테이블 스키마

#### Inventory (재고 관리)
```sql
CREATE TABLE inventory (
  product_id BIGINT PRIMARY KEY,
  stock INT NOT NULL,                  -- 현재 재고
  safety_stock INT,                    -- 안전 재고
  updated_at DATETIME(6) NOT NULL
);
```

#### Wallet (잔액 관리)
```sql
CREATE TABLE wallets (
  user_id BIGINT PRIMARY KEY,
  balance BIGINT NOT NULL,             -- 현재 잔액
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL
);

CREATE TABLE wallet_transactions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  type VARCHAR(20) NOT NULL,           -- TOPUP, DEBIT
  amount BIGINT NOT NULL,
  balance_after BIGINT NOT NULL,       -- 거래 후 잔액 (audit)
  idempotency_key VARCHAR(100),
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY idx_wt_idem (user_id, idempotency_key)
);
```

#### Coupon (쿠폰 관리)
```sql
CREATE TABLE coupons (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(50) UNIQUE NOT NULL,
  type VARCHAR(20) NOT NULL,           -- PERCENT, FIXED
  value INT NOT NULL,
  min_amount INT,
  max_discount INT,
  max_issuance INT,                    -- 최대 발급 수량
  starts_at DATETIME(6),
  ends_at DATETIME(6)
);

CREATE TABLE coupon_issuances (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  coupon_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  issued_at DATETIME(6) NOT NULL,
  redeem_count INT NOT NULL DEFAULT 0,
  UNIQUE KEY uq_issue (coupon_id, user_id)  -- 중복 발급 방지
);
```

---

## 2. 식별된 동시성 이슈

### 2.1 재고 감소 동시성 이슈 (Stock Oversell)

#### 문제 상황
```
[시나리오] 재고 1개 남은 상품을 2명이 동시에 주문

Thread A                        Thread B                     Inventory
─────────────────────────────────────────────────────────────────────
SELECT stock FROM inventory
WHERE product_id = 1
→ stock = 1
                                SELECT stock FROM inventory
                                WHERE product_id = 1
                                → stock = 1

CHECK: 1 >= 1 ✓
                                CHECK: 1 >= 1 ✓

UPDATE inventory
SET stock = 0
WHERE product_id = 1
                                UPDATE inventory
                                SET stock = -1  ⚠️
                                WHERE product_id = 1

✓ 주문 완료                      ✓ 주문 완료 (재고 음수!)
```

#### 현재 구현의 문제점

1. **구조적 문제**: 재고 차감이 주문 생성 이후에 발생
   ```java
   @Transactional
   public Result create(Command cmd) {
       // 1. Lock inventories
       lockInventories(productIds);

       // 2. Check stock
       verifyStock();

       // 3. Create order
       Order order = createReservedOrder();  // ← 여기서 주문 생성

       // 4. Reserve stock
       reserve(productId, qty, orderId);     // ← 여기서 재고 차감
       // ⚠️ 만약 이 단계에서 실패하면? 주문은 생성됨, 재고는 그대로!
   }
   ```

2. **중복 락 획득**: 불필요한 성능 저하
   ```java
   // OrderService.create()
   lockInventories(productIds);  // ← 1차 락 획득

   // InventoryService.reserve()
   invJpa.lockByProductIds(List.of(productId));  // ← 2차 락 획득 (중복!)
   ```

3. **Reflection 사용**: 타입 안정성 결여 및 에러 무시
   ```java
   try {
       var f = inv.getClass().getDeclaredField("stock");
       f.setAccessible(true);
       f.set(inv, inv.getStock() - qty);
   } catch (Exception ignore) {  // ⚠️ 모든 예외 무시!
   }
   ```

### 2.2 잔액 차감 동시성 이슈 (Negative Balance)

#### 문제 상황
```
[시나리오] 잔액 1000원인 유저가 동시에 1000원 결제 2번 시도

Thread A                        Thread B                     Wallet
─────────────────────────────────────────────────────────────────────
SELECT balance FROM wallets
WHERE user_id = 100
→ balance = 1000
                                SELECT balance FROM wallets
                                WHERE user_id = 100
                                → balance = 1000

CHECK: 1000 >= 1000 ✓
                                CHECK: 1000 >= 1000 ✓

UPDATE wallets
SET balance = 0
WHERE user_id = 100
                                UPDATE wallets
                                SET balance = -1000  ⚠️
                                WHERE user_id = 100

✓ 결제 성공                      ✓ 결제 성공 (잔액 음수!)
```

#### 현재 구현 상태

- ✅ **충전(Topup)**: Pessimistic Lock + Idempotency Key 사용
  ```java
  @Transactional
  public Result topup(Command cmd) {
      // Idempotency check
      var existing = rwPort.findTransaction(userId, idempotencyKey);
      if (existing.isPresent()) return existing.get();

      // Lock wallet
      Wallet wallet = rwPort.lockByUserId(userId).orElse(null);

      // Update balance
      wallet.setBalance(wallet.getBalance() + amount);
  }
  ```

- ❌ **차감(Debit)**: **미구현 상태**
  - 주문 생성 시 잔액 차감 로직 없음
  - `OrderService.create()` 메서드에 결제 처리 누락

### 2.3 쿠폰 발급 동시성 이슈 (Over-issuance)

#### 문제 상황
```
[시나리오] 선착순 100명 쿠폰에 1000명이 동시 요청

Thread A                        Thread B                     Coupon
─────────────────────────────────────────────────────────────────────
SELECT COUNT(*)
FROM coupon_issuances
WHERE coupon_id = 1
→ count = 99
                                SELECT COUNT(*)
                                FROM coupon_issuances
                                WHERE coupon_id = 1
                                → count = 99

CHECK: 99 < 100 ✓
                                CHECK: 99 < 100 ✓

INSERT INTO coupon_issuances
(coupon_id, user_id)
VALUES (1, 100)
→ count = 100
                                INSERT INTO coupon_issuances
                                (coupon_id, user_id)
                                VALUES (1, 200)
                                → count = 101  ⚠️

✓ 발급 성공                      ✓ 발급 성공 (초과 발급!)
```

#### 현재 구현 상태

- ❌ **쿠폰 발급 API**: **미구현 상태**
  - `data.sql`에서만 수동으로 발급
  - 선착순 제한 로직 없음

- ⚠️ **쿠폰 사용**: 부분 구현
  ```java
  // OrderService.create() - 쿠폰 적용만 가능
  if (cmd.couponCode() != null) {
      var couponOpt = couponPort.findApplicable(userId, couponCode, subtotal);
      discount = calculateDiscount(coupon);
  }
  ```
  - `redeem_count` 필드 존재하나 증가 로직 없음
  - 사용 횟수 제한 체크 없음

---

## 3. 구현 대상 선정

### 3.1 과제 요구사항

> **다음 항목 중 2개 이상 구현 및 테스트**
> - 재고 감소 동시성 제어
> - 잔액 차감 동시성 제어
> - 쿠폰 발급 동시성 제어

### 3.2 선정 기준

| 항목 | 현재 상태 | 작업량 | 비즈니스 중요도 | 우선순위 |
|------|-----------|--------|-----------------|----------|
| **재고 감소** | ⚠️ 부분 구현 (개선 필요) | 중 | 🔴 매우 높음 | **1순위** |
| **잔액 차감** | ❌ 미구현 | 중 | 🔴 매우 높음 | **2순위** |
| **쿠폰 발급** | ❌ 미구현 | 상 | 🟡 중간 | 3순위 |

### 3.3 최종 선정

✅ **재고 감소 동시성 제어** (개선)
- 기존 Pessimistic Lock 코드 리팩토링
- Reflection 제거, 원자적 업데이트 구현
- 멀티스레드 테스트 작성

✅ **잔액 차감 동시성 제어** (신규 구현)
- `WalletService.debit()` 메서드 추가
- `OrderService`에 결제 로직 통합
- Pessimistic Lock 적용
- 멀티스레드 테스트 작성

✅ **쿠폰 발급 동시성 제어** (신규 구현)
- `CouponService.issue()` API 추가
- 선착순 수량 제한 로직
- Pessimistic Lock 또는 Optimistic Lock 적용
- 멀티스레드 테스트 작성

---

## 4. 해결 전략

### 4.1 동시성 제어 기법 비교

| 기법 | 장점 | 단점 | 적합한 시나리오 |
|------|------|------|----------------|
| **Pessimistic Lock** (비관적 락) | • 데이터 정합성 100% 보장<br>• 구현 간단 | • 성능 저하 (Lock 대기)<br>• Deadlock 위험 | • 충돌이 빈번한 경우<br>• 데이터 정합성이 최우선 |
| **Optimistic Lock** (낙관적 락) | • 성능 우수 (Lock 없음)<br>• Deadlock 없음 | • 충돌 시 재시도 필요<br>• 복잡한 에러 처리 | • 충돌이 드문 경우<br>• 읽기가 많은 경우 |
| **Conditional UPDATE** (조건부 갱신) | • 성능 우수<br>• 간단한 로직 | • 복잡한 비즈니스 로직 표현 어려움 | • 단순 감소/증가 연산<br>• 단일 레코드 업데이트 |

### 4.2 적용 전략

#### 4.2.1 재고 감소: Pessimistic Lock → Optimistic Lock 전환

**선택 이유**
- 재고 조회는 빈번하지만, 실제 구매는 상대적으로 드물다
- Lock으로 인한 성능 저하 완화
- 실패 시 재시도로 UX 향상 가능

**구현 방식**
```java
@Entity
@Table(name = "inventory")
public class InventoryEntity {
    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(nullable = false)
    private Integer stock;

    @Version  // ← Optimistic Lock
    private Long version;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;
}
```

**장점**
- 읽기 성능 향상 (Lock 대기 없음)
- 동시 접속자 증가 시에도 성능 유지
- Deadlock 발생 가능성 제거

**단점**
- 재시도 로직 필요 (충돌 시 `OptimisticLockException`)
- 사용자에게 "재고 부족" 메시지 표시 가능

#### 4.2.2 잔액 차감: Pessimistic Lock 유지

**선택 이유**
- 결제는 절대 실패하면 안 되는 Critical Path
- 잔액 음수 방지가 최우선 (데이터 정합성)
- 사용자당 순차 처리 필요

**구현 방식**
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT w FROM WalletEntity w WHERE w.userId = :userId")
Optional<WalletEntity> lockByUserId(@Param("userId") Long userId);
```

**트랜잭션 순서**
```
1. 잔액 Lock 획득 (SELECT ... FOR UPDATE)
2. 잔액 검증 (balance >= amount)
3. 잔액 차감 (balance = balance - amount)
4. Transaction 기록
5. Commit
```

**장점**
- 잔액 음수 발생 100% 차단
- 결제 실패 가능성 최소화

**단점**
- 동일 사용자의 동시 결제 시 대기 시간 발생
- (실무에서는 동일 유저가 동시 결제하는 경우가 거의 없음)

#### 4.2.3 쿠폰 발급: Conditional UPDATE

**선택 이유**
- 선착순 이벤트는 초당 수천~수만 건 요청
- Lock 방식은 성능 병목 발생
- Atomic Increment로 정확한 카운팅 가능

**구현 방식 (Option 1: Counter 테이블)**
```sql
-- 쿠폰 발급 카운터 테이블 추가
CREATE TABLE coupon_issuance_counters (
  coupon_id BIGINT PRIMARY KEY,
  issued_count INT NOT NULL DEFAULT 0,
  max_count INT NOT NULL,
  updated_at DATETIME(6) NOT NULL
);

-- Atomic Increment
UPDATE coupon_issuance_counters
SET issued_count = issued_count + 1,
    updated_at = NOW()
WHERE coupon_id = ?
  AND issued_count < max_count;  -- 조건부 업데이트
```

**구현 방식 (Option 2: Redis Incr)**
```java
// Redis Atomic Counter
Long count = redisTemplate.opsForValue().increment("coupon:1:count");

if (count <= maxCount) {
    // 발급 처리
    couponIssuanceJpa.save(new CouponIssuanceEntity(...));
} else {
    // 초과 발급 방지
    throw new CouponExhaustedException("쿠폰이 모두 소진되었습니다.");
}
```

**장점**
- 초고속 처리 가능 (Lock 없음)
- Deadlock 없음
- Scale-out 가능

**단점**
- Redis 사용 시 DB와 동기화 필요
- Counter와 실제 발급 사이 간극 가능 (보상 트랜잭션 필요)

### 4.3 선택 기법 정리

| 기능 | 선택 기법 | 이유 |
|------|-----------|------|
| **재고 감소** | Optimistic Lock | 성능과 정합성 균형 |
| **잔액 차감** | Pessimistic Lock | 정합성 최우선 |
| **쿠폰 발급** | Conditional UPDATE (Redis) | 고성능 필요 |

---

## 5. 구현 계획

### 5.1 Phase 1: 재고 감소 개선

#### 5.1.1 구현 항목
- [ ] `InventoryEntity`에 `@Version` 필드 추가
- [ ] `InventoryService.reserve()` 리팩토링
  - Reflection 제거
  - 정상적인 JPA 업데이트 사용
  - 재고 차감 실패 시 예외 처리
- [ ] `OrderService.create()` 중복 락 제거
- [ ] 재고 차감을 주문 생성 이전으로 이동 (원자성 보장)

#### 5.1.2 테스트 시나리오
```java
@Test
void 동시에_100명이_재고_1개_상품_주문시_1명만_성공() {
    // Given: 재고 1개 상품
    Long productId = 1L;
    setStock(productId, 1);

    // When: 100개 스레드가 동시에 주문
    int threadCount = 100;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);

    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failCount = new AtomicInteger(0);

    for (int i = 0; i < threadCount; i++) {
        final long userId = i + 1;
        executor.submit(() -> {
            try {
                orderService.create(new CreateOrderCommand(userId, productId, 1));
                successCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            } finally {
                latch.countDown();
            }
        });
    }

    latch.await(10, TimeUnit.SECONDS);
    executor.shutdown();

    // Then: 정확히 1명만 성공
    assertThat(successCount.get()).isEqualTo(1);
    assertThat(failCount.get()).isEqualTo(99);
    assertThat(getStock(productId)).isEqualTo(0);
}
```

### 5.2 Phase 2: 잔액 차감 구현

#### 5.2.1 구현 항목
- [ ] `WalletService.debit()` 메서드 추가
  ```java
  @Transactional
  public Result debit(Command cmd) {
      // 1. Lock wallet
      Wallet wallet = rwPort.lockByUserId(cmd.userId())
          .orElseThrow(() -> new WalletNotFoundException());

      // 2. Validate balance
      if (wallet.getBalance() < cmd.amount()) {
          throw new InsufficientBalanceException();
      }

      // 3. Debit balance
      long newBalance = wallet.getBalance() - cmd.amount();
      wallet.setBalance(newBalance);

      // 4. Record transaction
      rwPort.saveTransaction(new WalletTransaction(
          cmd.userId(),
          "DEBIT",
          cmd.amount(),
          newBalance,
          cmd.idempotencyKey()
      ));

      return new Result(wallet.getUserId(), newBalance);
  }
  ```

- [ ] `OrderService.create()`에 결제 로직 추가
  ```java
  @Transactional
  public Result create(Command cmd) {
      // ... 기존 로직 ...

      // Payment processing
      walletService.debit(new DebitCommand(
          cmd.userId(),
          total,
          "ORDER:" + orderNo  // idempotency key
      ));

      // ... 주문 완료 ...
  }
  ```

- [ ] Idempotency 보장
  - `wallet_transactions.idempotency_key` 활용
  - 중복 차감 방지

#### 5.2.2 테스트 시나리오
```java
@Test
void 잔액_1000원_유저가_동시에_1000원_결제_2번_시도시_1번만_성공() {
    // Given: 잔액 1000원
    Long userId = 1L;
    walletService.topup(new TopupCommand(userId, 1000L, "INIT"));

    // When: 2개 스레드가 동시에 1000원 차감
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch latch = new CountDownLatch(2);

    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failCount = new AtomicInteger(0);

    for (int i = 0; i < 2; i++) {
        executor.submit(() -> {
            try {
                orderService.create(new CreateOrderCommand(userId, 1L, 1, 1000L));
                successCount.incrementAndGet();
            } catch (InsufficientBalanceException e) {
                failCount.incrementAndGet();
            } finally {
                latch.countDown();
            }
        });
    }

    latch.await(10, TimeUnit.SECONDS);
    executor.shutdown();

    // Then: 1번만 성공, 잔액 0원
    assertThat(successCount.get()).isEqualTo(1);
    assertThat(failCount.get()).isEqualTo(1);
    assertThat(getBalance(userId)).isEqualTo(0L);
}
```

### 5.3 Phase 3: 쿠폰 발급 구현

#### 5.3.1 구현 항목
- [ ] Redis 설정 추가 (Atomic Counter용)
- [ ] `CouponService.issue()` API 구현
  ```java
  @Transactional
  public Result issue(Command cmd) {
      // 1. Redis Atomic Increment
      String key = "coupon:" + cmd.couponId() + ":count";
      Long count = redisTemplate.opsForValue().increment(key);

      // 2. Check limit
      Coupon coupon = couponPort.findById(cmd.couponId())
          .orElseThrow(() -> new CouponNotFoundException());

      if (count > coupon.getMaxIssuance()) {
          // Compensate
          redisTemplate.opsForValue().decrement(key);
          throw new CouponExhaustedException("쿠폰이 소진되었습니다.");
      }

      // 3. Issue coupon
      try {
          CouponIssuance issuance = new CouponIssuance(
              cmd.couponId(),
              cmd.userId(),
              OffsetDateTime.now(),
              0  // redeem_count
          );
          couponPort.save(issuance);
      } catch (DataIntegrityViolationException e) {
          // 이미 발급된 경우 (UNIQUE 제약 위반)
          redisTemplate.opsForValue().decrement(key);
          throw new DuplicateIssuanceException("이미 발급받은 쿠폰입니다.");
      }

      return new Result(cmd.couponId(), cmd.userId());
  }
  ```

- [ ] `IssueCouponController` REST API 추가
  ```java
  @PostMapping("/coupons/{couponId}/issue")
  public ResponseEntity<?> issue(
      @PathVariable Long couponId,
      @AuthenticationPrincipal Long userId
  ) {
      var result = couponService.issue(new IssueCommand(couponId, userId));
      return ResponseEntity.ok(result);
  }
  ```

- [ ] Redis Counter 초기화 로직
  ```java
  @PostConstruct
  public void initCouponCounters() {
      List<Coupon> coupons = couponRepository.findAll();
      for (Coupon coupon : coupons) {
          String key = "coupon:" + coupon.getId() + ":count";
          Long issued = couponIssuanceRepository.countByCouponId(coupon.getId());
          redisTemplate.opsForValue().set(key, issued);
      }
  }
  ```

#### 5.3.2 테스트 시나리오
```java
@Test
void 선착순_100명_쿠폰에_1000명_동시_요청시_100명만_발급() {
    // Given: 선착순 100명 쿠폰
    Long couponId = 1L;
    Coupon coupon = new Coupon(couponId, "WELCOME100", 100);  // max 100
    couponRepository.save(coupon);

    // When: 1000개 스레드가 동시 발급 요청
    int threadCount = 1000;
    ExecutorService executor = Executors.newFixedThreadPool(100);
    CountDownLatch latch = new CountDownLatch(threadCount);

    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failCount = new AtomicInteger(0);

    for (int i = 0; i < threadCount; i++) {
        final long userId = i + 1;
        executor.submit(() -> {
            try {
                couponService.issue(new IssueCommand(couponId, userId));
                successCount.incrementAndGet();
            } catch (CouponExhaustedException e) {
                failCount.incrementAndGet();
            } finally {
                latch.countDown();
            }
        });
    }

    latch.await(30, TimeUnit.SECONDS);
    executor.shutdown();

    // Then: 정확히 100명만 발급
    assertThat(successCount.get()).isEqualTo(100);
    assertThat(failCount.get()).isEqualTo(900);

    Long issuedCount = couponIssuanceRepository.countByCouponId(couponId);
    assertThat(issuedCount).isEqualTo(100L);

    String redisKey = "coupon:" + couponId + ":count";
    Long redisCount = redisTemplate.opsForValue().get(redisKey);
    assertThat(redisCount).isEqualTo(100L);
}
```

### 5.4 Phase 4: 문서화

#### 5.4.1 작성할 문서
- [ ] `docs/claude-code/concurrency-test-results.md`
  - 각 시나리오별 테스트 결과
  - 성능 측정 (TPS, 응답시간)
  - 실패 케이스 분석

- [ ] README.md 업데이트
  - 동시성 제어 기능 설명 추가
  - 테스트 실행 방법 추가

#### 5.4.2 문서 구조
```markdown
# 동시성 제어 테스트 결과

## 1. 재고 감소 동시성 테스트
### 테스트 시나리오
### 적용 기법: Optimistic Lock
### 테스트 결과
- 성공: 1건
- 실패: 99건
- 최종 재고: 0
- 소요 시간: XXms

## 2. 잔액 차감 동시성 테스트
### 테스트 시나리오
### 적용 기법: Pessimistic Lock
### 테스트 결과
...

## 3. 쿠폰 발급 동시성 테스트
### 테스트 시나리오
### 적용 기법: Redis Atomic Counter
### 테스트 결과
...
```

---

## 6. 예상 이슈 및 대응 방안

### 6.1 Optimistic Lock Retry 전략

**문제**: 충돌 시 무한 재시도로 인한 성능 저하

**해결**:
```java
@Retryable(
    value = {OptimisticLockException.class},
    maxAttempts = 3,
    backoff = @Backoff(delay = 100)
)
public void reserve(Long productId, int qty) {
    // ... retry logic
}
```

### 6.2 Redis와 DB 동기화 이슈

**문제**: Redis 카운터와 실제 DB 발급 수가 불일치

**해결**:
1. Application 시작 시 Redis 초기화
2. 주기적 동기화 배치 (Scheduled Task)
3. 보상 트랜잭션 (Saga Pattern)

### 6.3 Deadlock 발생 가능성

**문제**: 여러 자원을 동시에 Lock 할 때 Deadlock

**해결**:
- Lock 순서 일관성 유지 (항상 productId 오름차순)
- Timeout 설정 (`@Lock(timeout = 5000)`)
- Deadlock 감지 및 재시도

---

## 7. 성능 목표

| 지표 | 목표 | 측정 방법 |
|------|------|-----------|
| **재고 감소 TPS** | 1000 TPS | JMeter 부하 테스트 |
| **잔액 차감 응답시간** | < 100ms | @Timed 메트릭 |
| **쿠폰 발급 TPS** | 5000 TPS | Redis Benchmark |
| **동시성 정합성** | 100% | 멀티스레드 테스트 |

---

## 8. 참고 자료

- [Spring Data JPA Locking](https://docs.spring.io/spring-data/jpa/docs/current/reference/html/#locking)
- [Redis Atomic Operations](https://redis.io/commands/incr/)
- [Optimistic vs Pessimistic Locking](https://vladmihalcea.com/optimistic-vs-pessimistic-locking/)
- [Database Concurrency Control](https://www.postgresql.org/docs/current/mvcc-intro.html)

---

**문서 작성일**: 2025-10-29
**작성자**: Claude Code
**버전**: 1.0.0
