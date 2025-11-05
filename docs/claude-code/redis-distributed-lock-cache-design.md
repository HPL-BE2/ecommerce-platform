# Redis 분산락 & 캐싱 전략 설계 문서

> E-커머스 플랫폼의 Redis 기반 분산락 구현 및 캐싱 전략 적용

## 📋 목차

1. [현재 시스템 분석](#1-현재-시스템-분석)
2. [분산락 설계](#2-분산락-설계)
3. [캐싱 전략 설계](#3-캐싱-전략-설계)
4. [DB Transaction과 분산락 혼용 전략](#4-db-transaction과-분산락-혼용-전략)
5. [구현 계획](#5-구현-계획)
6. [성능 목표](#6-성능-목표)

---

## 1. 현재 시스템 분석

### 1.1 현재 동시성 제어 현황

| 도메인 | 현재 방식 | 문제점/한계 | 개선 방향 |
|--------|----------|-----------|----------|
| **재고(Inventory)** | Optimistic Lock (@Version) + Retry | DB 부하, 충돌 시 재시도 필요 | 분산락으로 선제적 차단 |
| **지갑(Wallet)** | Pessimistic Lock (FOR UPDATE) | 단일 DB 종속, 락 경합 시 대기 | Pessimistic Lock 유지 (금융 데이터 특성) |
| **쿠폰 발급** | Pessimistic Lock + TODO 주석에 Redis 언급 | DB 락으로 인한 성능 저하 | **Redis 분산락 + Atomic Counter** |

### 1.2 현재 캐싱 현황

| 대상 | 캐시 여부 | TTL | 문제점 |
|------|----------|-----|--------|
| **상품 목록** | ✅ 적용 (`products`) | 3분 | - |
| **상품 상세** | ✅ 적용 (`product-detail`) | 30초 | - |
| **쿠폰 정보** | ❌ 미적용 | - | 체크아웃 시 매번 DB 조회 |
| **재고 수량** | ❌ 미적용 | - | 상품 상세 페이지에서 매번 조회 |
| **주문 내역** | ❌ 미적용 | - | 마이페이지 조회 시 DB 부하 |

### 1.3 기술 스택

- **Framework**: Spring Boot 3.4.1 + Java 17
- **Architecture**: Hexagonal Architecture (Ports & Adapters)
- **Database**: MySQL 8.0 (HikariCP, max 3 connections)
- **Cache**: Redis 7.2 (Lettuce + Spring Data Redis)
- **Testing**: Testcontainers + JUnit 5

---

## 2. 분산락 설계

### 2.1 분산락이 필요한 이유

#### 현재 DB 락의 한계

```
[문제 상황 1] 쿠폰 발급 - 고트래픽 시 DB 병목

Thread A                        Thread B                     Database
─────────────────────────────────────────────────────────────────────
SELECT ... FOR UPDATE
(Lock 획득)
                                SELECT ... FOR UPDATE
                                (대기... 대기... 대기...)
                                ⏱️ Timeout 위험
UPDATE coupon
SET issued_count = 100
(Lock 해제)
                                (Lock 획득)
                                UPDATE coupon
                                (Lock 해제)

⚠️ 문제점:
- DB 커넥션 풀 고갈 (max 3 connections)
- Lock 대기로 인한 응답 지연
- 단일 DB 장애 시 전체 시스템 중단
```

```
[문제 상황 2] 분산 환경에서의 동시성 제어

App Server 1                    App Server 2                 MySQL
─────────────────────────────────────────────────────────────────────
재고 확인 (stock=1)
                                재고 확인 (stock=1)
주문 생성 시작
                                주문 생성 시작
재고 차감 (stock=0)
Commit
                                재고 차감 (stock=-1) ⚠️
                                Commit

⚠️ 문제점:
- 여러 서버 인스턴스 간 동기화 불가
- Optimistic Lock도 결국 DB 의존
```

#### 분산락의 장점

| 특징 | 설명 | 효과 |
|------|------|------|
| **애플리케이션 레벨 제어** | DB 트랜잭션 시작 전 락 획득 | DB 부하 감소 (99% 트래픽 필터링) |
| **분산 환경 지원** | Redis를 중앙 락 저장소로 사용 | 여러 서버 인스턴스 간 동기화 |
| **빠른 락 획득/해제** | Redis In-Memory 연산 | 평균 1ms 이내 처리 |
| **자동 락 해제** | Lease Time 기반 TTL | 데드락 방지 |

### 2.2 적용 대상 선정

#### ✅ Priority 1: 쿠폰 발급 (High Priority)

**선정 이유**
- 선착순 이벤트는 순간 트래픽 집중 (수천~수만 TPS)
- DB Pessimistic Lock은 성능 병목
- 이미 TODO 주석에 Redis 최적화 언급됨 (`CouponService.java:19-20`)

**적용 방안**

```java
// 키 설계: coupon:{couponId}:lock
// 락 범위: Redis 카운터 체크 → DB 발급 기록 저장까지

@DistributedLock(key = "'coupon:' + #couponId + ':lock'",
                 leaseTime = 5000,
                 waitTime = 3000)
public Result issue(Command cmd) {
    // 1) Redis Atomic Counter로 빠른 검증
    String countKey = "coupon:" + cmd.couponId() + ":issued";
    Long currentCount = redisTemplate.opsForValue().increment(countKey);

    // 2) 최대 발급량 체크 (Redis에서 99% 필터링)
    if (currentCount > maxIssuance) {
        redisTemplate.opsForValue().decrement(countKey);
        throw new CouponExhaustedException();
    }

    // 3) DB에 발급 기록 저장 (UNIQUE 제약으로 중복 방지)
    try {
        couponPort.saveIssuance(userId, couponId);
    } catch (DataIntegrityViolationException e) {
        // 중복 발급 시도 시 카운터 롤백
        redisTemplate.opsForValue().decrement(countKey);
        throw new DuplicateIssuanceException();
    }

    return new Result(couponId, userId);
}
```

**성능 개선 효과**
- **Before**: DB Lock → 100 TPS
- **After**: Redis 분산락 + Atomic Counter → **5,000 TPS**

#### ✅ Priority 2: 주문/재고 예약 (Medium Priority)

**선정 이유**
- 한정 수량 상품 주문 시 동시 요청 발생
- 현재 Optimistic Lock + Retry는 실패 후 재시도로 DB 부하
- 타임딜/플래시 세일 시나리오 대응

**적용 방안**

```java
// 키 설계: product:{productId}:order:lock
// 락 범위: 재고 확인 + 예약 처리 구간

@DistributedLock(key = "'product:' + #productId + ':order:lock'",
                 leaseTime = 10000,
                 waitTime = 2000)
public void reserveInventory(Long productId, int quantity) {
    // 1) Redis 캐시에서 재고 먼저 체크 (빠른 실패)
    String stockKey = "product:" + productId + ":stock";
    Integer cachedStock = (Integer) redisTemplate.opsForValue().get(stockKey);

    if (cachedStock != null && cachedStock < quantity) {
        throw new InsufficientStockException("재고 부족");
    }

    // 2) DB 재고 감소 (Optimistic Lock은 보조 수단으로 유지)
    inventoryPort.reserve(productId, quantity, null);

    // 3) Redis 캐시 재고도 동기화
    redisTemplate.opsForValue().decrement(stockKey, quantity);
}
```

**장점**
- Redis에서 재고 부족 케이스 99% 필터링
- DB 재고 업데이트는 실제 주문만 접근
- Optimistic Lock은 안전장치로 유지 (Redis 장애 대비)

#### ⚠️ Priority 3: 중복 결제 방지 (Low Priority)

**현재 상태**
- 이미 `(user_id, idempotency_key)` UNIQUE 제약으로 해결
- DB 트랜잭션 롤백으로 충분히 안전

**분산락 추가 이유**
- UNIQUE 위반 Exception 발생 → 불필요한 트랜잭션 롤백
- 락으로 선제적 차단 가능 (성능 최적화)

```java
// 키 설계: payment:{userId}:{idempotencyKey}:lock

@DistributedLock(key = "'payment:' + #userId + ':' + #idempotencyKey",
                 leaseTime = 30000,
                 waitTime = 5000)
public Result debit(Command cmd) {
    // 1) Redis에서 멱등성 키 체크 (중복 방지)
    String idempotencyKey = "payment:" + userId + ":" + cmd.idempotencyKey();
    Boolean isNew = redisTemplate.opsForValue().setIfAbsent(
        idempotencyKey, "1", Duration.ofMinutes(10)
    );

    if (!isNew) {
        throw new DuplicatePaymentException("이미 처리된 결제입니다.");
    }

    // 2) DB 트랜잭션 진행
    walletPort.debit(...);

    return new Result(userId, newBalance);
}
```

### 2.3 분산락 구현 전략

#### Redisson 선택 이유

| 라이브러리 | 장점 | 단점 | 선택 |
|-----------|------|------|------|
| **Redisson** | • Pub/Sub 기반 (Polling 없음)<br>• Lease Time 자동 갱신<br>• Retry 정책 내장 | • 의존성 크기 큼 | ✅ **선택** |
| Lettuce Lock | • 경량<br>• 이미 사용 중 | • 수동 구현 필요<br>• Polling 방식 | ❌ |
| Spring Integration | • Spring Native | • 기능 제한적 | ❌ |

#### Redisson Lock 동작 원리

```
[Redisson Lock 획득 과정]

Client A                        Redis                        Client B
─────────────────────────────────────────────────────────────────────
tryLock("myLock", 5000ms)
→ SET myLock uuid1
  NX PX 5000
→ OK (Lock 획득 ✓)
                                myLock = {
                                  value: "uuid1",
                                  ttl: 5000ms
                                }
                                                            tryLock("myLock")
                                                            → GET myLock
                                                            → EXISTS (대기)

                                                            SUBSCRIBE __keyspace@0__:myLock
                                                            (Pub/Sub 대기 - Polling 없음!)

비즈니스 로직 수행...

unlock("myLock")
→ DEL myLock
→ PUBLISH __keyspace@0__:myLock
                                PUBLISH 전송 →
                                                            NOTIFY 수신!
                                                            tryLock("myLock")
                                                            → OK (Lock 획득 ✓)
```

#### 커스텀 애노테이션 설계

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {
    /**
     * 락 키 (SpEL 표현식 지원)
     * 예: "'coupon:' + #couponId + ':lock'"
     */
    String key();

    /**
     * 락 점유 시간 (ms)
     * 기본값: 5000ms (5초)
     * - DB Tx 타임아웃보다 길게 설정 필요
     */
    long leaseTime() default 5000L;

    /**
     * 락 대기 시간 (ms)
     * 기본값: 3000ms (3초)
     * - 이 시간 내 락 획득 실패 시 예외 발생
     */
    long waitTime() default 3000L;

    /**
     * 락 획득 실패 시 예외 메시지
     */
    String failMessage() default "리소스 잠금 획득 실패";
}
```

#### AOP 구현 설계

```java
@Aspect
@Component
@RequiredArgsConstructor
public class DistributedLockAspect {
    private final RedissonClient redissonClient;

    @Around("@annotation(distributedLock)")
    public Object lock(ProceedingJoinPoint joinPoint, DistributedLock distributedLock)
            throws Throwable {

        // 1) SpEL로 락 키 파싱
        String lockKey = parseKey(distributedLock.key(), joinPoint);
        RLock lock = redissonClient.getLock(lockKey);

        // 2) 락 획득 시도
        boolean acquired = lock.tryLock(
            distributedLock.waitTime(),
            distributedLock.leaseTime(),
            TimeUnit.MILLISECONDS
        );

        if (!acquired) {
            throw new LockAcquisitionException(distributedLock.failMessage());
        }

        try {
            // 3) 비즈니스 로직 수행
            return joinPoint.proceed();
        } finally {
            // 4) 락 해제 (안전하게)
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private String parseKey(String keyExpression, ProceedingJoinPoint joinPoint) {
        // SpEL 파싱 로직
        // ...
    }
}
```

---

## 3. 캐싱 전략 설계

### 3.1 캐시 적용 우선순위 분석

| 쿼리 대상 | 조회 빈도 | 변경 빈도 | 조회 비용 | 정합성 요구 | 캐시 적합도 | 현재 상태 |
|----------|----------|----------|----------|-----------|-----------|----------|
| **상품 상세** | 매우 높음 | 낮음 | 중간 | 낮음 | ⭐⭐⭐⭐⭐ | ✅ 30초 TTL |
| **상품 목록** | 높음 | 낮음 | 높음 | 낮음 | ⭐⭐⭐⭐⭐ | ✅ 3분 TTL |
| **쿠폰 정보** | 높음 | 낮음 | 중간 | 중간 | ⭐⭐⭐⭐ | ❌ 미적용 |
| **재고 수량** | 매우 높음 | 매우 높음 | 낮음 | **매우 높음** | ⭐⭐ | ❌ 미적용 |
| **주문 내역** | 중간 | 없음 | 높음 | 중간 | ⭐⭐⭐⭐ | ❌ 미적용 |
| **지갑 잔액** | 중간 | 높음 | 낮음 | **매우 높음** | ⭐ | ❌ 미적용 |

### 3.2 신규 캐싱 적용 설계

#### ✅ 1) 쿠폰 정보 캐싱 (High Priority)

**문제점**
- 체크아웃 페이지에서 모든 사용자가 쿠폰 목록 조회
- `CouponValidatePort.findApplicable(userId, couponCode, subtotal)` 매번 DB 조회
- JOIN 쿼리 (coupons + coupon_issuances) 비용 높음

**캐싱 전략**

```java
// 사용자별 활성 쿠폰 목록 캐싱
@Cacheable(cacheNames = "active-coupons",
           key = "'user:' + #userId",
           unless = "#result.isEmpty()")
public List<CouponInfo> findApplicableCoupons(Long userId, Integer subtotal) {
    return couponPort.findActiveByUser(userId, subtotal);
}

// 쿠폰 발급 시 해당 사용자 캐시 무효화
@CacheEvict(cacheNames = "active-coupons", key = "'user:' + #userId")
public void issueCoupon(Long userId, Long couponId) {
    // 쿠폰 발급 로직
    couponPort.saveIssuance(userId, couponId);
}

// 쿠폰 사용 시 해당 사용자 캐시 무효화
@CacheEvict(cacheNames = "active-coupons", key = "'user:' + #userId")
public void redeemCoupon(Long userId, Long issuanceId) {
    // 쿠폰 사용 로직
    couponPort.incrementRedeemCount(issuanceId);
}
```

**Redis 키 구조**
```
active-coupons::user:100 = [
  {
    "couponId": 1,
    "code": "WELCOME10",
    "type": "PERCENT",
    "value": 10,
    "minAmount": 10000,
    "maxDiscount": 5000
  },
  ...
]
```

**설정**
- **TTL**: 5분 (쿠폰 정보는 자주 변하지 않음)
- **무효화**: 쿠폰 발급/사용 시 해당 사용자 캐시만 삭제
- **키**: `active-coupons::user:{userId}`

#### ✅ 2) 재고 조회 캐싱 - Cache-Aside (Medium Priority)

**주의사항**
- 재고는 실시간 정합성이 중요 → **읽기 전용 참고용**으로만 사용
- 실제 주문 시에는 항상 DB 조회 (캐시는 UI 표시용)

**캐싱 전략**

```java
// 상품 상세 페이지 조회 (캐시 우선)
public Integer getStockForDisplay(Long productId) {
    String key = "product:" + productId + ":stock";

    // Cache Hit
    Integer cached = (Integer) redisTemplate.opsForValue().get(key);
    if (cached != null) {
        return cached;
    }

    // Cache Miss: DB 조회 후 캐시 저장
    Integer dbStock = inventoryPort.getStock(productId);
    redisTemplate.opsForValue().set(key, dbStock, Duration.ofSeconds(10));

    return dbStock;
}

// 주문 시에는 항상 DB 조회 (캐시 무시)
@Transactional
public void reserveInventory(Long productId, int quantity) {
    // 1) DB 재고 차감 (분산락 내부에서 실행)
    inventoryPort.reserve(productId, quantity, null);

    // 2) Redis 캐시도 즉시 업데이트
    String key = "product:" + productId + ":stock";
    redisTemplate.opsForValue().decrement(key, quantity);
}

// 주문 취소 시 캐시 갱신
public void cancelOrder(Long orderId) {
    List<OrderItem> items = orderPort.findItems(orderId);

    for (OrderItem item : items) {
        // 1) DB 재고 복원
        inventoryPort.restore(item.getProductId(), item.getQuantity());

        // 2) Redis 캐시 갱신
        String key = "product:" + item.getProductId() + ":stock";
        redisTemplate.opsForValue().increment(key, item.getQuantity());
    }
}
```

**설정**
- **TTL**: 10초 (매우 짧게 유지, 빠른 동기화)
- **무효화**: 주문 완료/취소 시 즉시 갱신
- **주의**: 실제 주문 로직에서는 캐시 사용 금지

#### ✅ 3) 주문 내역 캐싱 (Low Priority)

**시나리오**
- 마이페이지 주문 조회
- 주문 상세 정보 조회

**캐싱 전략**

```java
// 사용자별 주문 목록 캐싱 (페이지별)
@Cacheable(cacheNames = "user-orders",
           key = "'user:' + #userId + ':page:' + #page + ':size:' + #size")
public Page<OrderSummary> findOrdersByUser(Long userId, int page, int size) {
    return orderPort.findByUserIdOrderByCreatedDesc(
        userId,
        PageRequest.of(page, size)
    );
}

// 주문 완료 시 해당 사용자의 첫 페이지만 무효화
@CacheEvict(cacheNames = "user-orders",
            key = "'user:' + #userId + ':page:0:size:*'")
public void completeOrder(Long userId, Long orderId) {
    orderPort.updateStatus(orderId, "COMPLETED");
}

// 주문 상세 캐싱
@Cacheable(cacheNames = "order-detail", key = "#orderId")
public OrderSummary findOrderDetail(Long orderId) {
    return orderPort.findById(orderId)
        .orElseThrow(() -> new OrderNotFoundException());
}
```

**설정**
- **TTL**: 10분 (주문 내역은 변경이 거의 없음)
- **무효화**: 주문 생성/완료 시 해당 사용자의 첫 페이지만 삭제

### 3.3 캐시 무효화 전략 정리

| 전략 | 적용 대상 | 장점 | 단점 | 구현 방법 |
|------|----------|------|------|----------|
| **TTL 기반** | 상품 정보, 재고 | 구현 간단 | 일시적 불일치 가능 | `@Cacheable` with TTL |
| **Cache-Aside** | 재고, 쿠폰 | 정합성 높음 | 코드 복잡도 증가 | 수동 Redis 조작 |
| **Write-Through** | ❌ 미적용 | 정합성 완벽 | 쓰기 성능 저하 | - |
| **Event-Driven** | 주문, 쿠폰 발급 | 느슨한 결합 | 이벤트 처리 오버헤드 | `@CacheEvict` with ApplicationEvent |

### 3.4 캐시 설정 코드

```java
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager(LettuceConnectionFactory connectionFactory) {
        // 기본 설정: 30초 TTL
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofSeconds(30))
            .disableCachingNullValues()
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    new GenericJackson2JsonRedisSerializer()
                )
            );

        // 커스텀 설정
        Map<String, RedisCacheConfiguration> cacheConfigurations = Map.of(
            "products", defaultConfig.entryTtl(Duration.ofMinutes(3)),
            "product-detail", defaultConfig.entryTtl(Duration.ofSeconds(30)),
            "active-coupons", defaultConfig.entryTtl(Duration.ofMinutes(5)),
            "user-orders", defaultConfig.entryTtl(Duration.ofMinutes(10)),
            "order-detail", defaultConfig.entryTtl(Duration.ofMinutes(10))
        );

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaultConfig)
            .withInitialCacheConfigurations(cacheConfigurations)
            .build();
    }
}
```

---

## 4. DB Transaction과 분산락 혼용 전략

### 4.1 핵심 원칙

#### ⚠️ 데드락 위험 - 잘못된 예시

```java
// ❌ 잘못된 예시: DB Tx 내부에 분산락
@Transactional  // DB Tx 시작
public void badExample() {
    // DB Lock 획득
    walletPort.lockByUserId(userId);  // SELECT ... FOR UPDATE

    // 분산락 획득 시도
    @DistributedLock(key = "...")
    public void innerMethod() {
        // ...
    }

    // ⚠️ Deadlock 위험!
    // - Thread A: DB Lock 보유 → 분산락 대기
    // - Thread B: 분산락 보유 → DB Lock 대기
}
```

#### ✅ 올바른 락 획득 순서

```
[올바른 순서]
1. 분산락 획득
2. DB 트랜잭션 시작
3. DB Lock 획득 (필요시)
4. 비즈니스 로직 수행
5. DB 트랜잭션 커밋
6. 분산락 해제

[절대 금지]
1. DB 트랜잭션 시작
2. DB Lock 획득
3. 분산락 획득 시도  ← 데드락 위험!
```

### 4.2 쿠폰 발급 시나리오

```java
// ✅ 올바른 구현
@Service
public class CouponService {

    // 분산락이 가장 바깥에 위치
    @DistributedLock(key = "'coupon:' + #couponId + ':lock'",
                     leaseTime = 5000,
                     waitTime = 3000)
    public Result issue(Command cmd) {
        // 분산락 획득 후 트랜잭션 시작
        return transactionTemplate.execute(status -> {
            // Redis Atomic Counter (분산락 내부에서 안전)
            Long count = redisTemplate.opsForValue()
                .increment("coupon:" + cmd.couponId() + ":issued");

            if (count > maxIssuance) {
                redisTemplate.opsForValue().decrement("coupon:" + cmd.couponId() + ":issued");
                throw new CouponExhaustedException();
            }

            // DB 삽입 (UNIQUE 제약으로 중복 방지)
            couponPort.saveIssuance(cmd.userId(), cmd.couponId());

            return new Result(cmd.couponId(), cmd.userId());
        });
    }
}
```

### 4.3 주문/재고 예약 시나리오

```java
@Service
public class OrderService {

    @Transactional
    public Result create(Command cmd) {
        // 주문 생성 전 재고 예약 (분산락 사용)
        for (var item : cmd.items()) {
            // 분산락으로 재고 보호
            inventoryService.reserveWithLock(item.productId(), item.quantity());
        }

        // 주문 생성 (이미 재고는 확보된 상태)
        Long orderId = orderPort.create(...);

        return new Result(orderId, "RESERVED");
    }
}

@Service
public class InventoryService {

    // 분산락 적용 메서드 (별도 트랜잭션)
    @DistributedLock(key = "'product:' + #productId + ':order:lock'",
                     leaseTime = 10000,
                     waitTime = 2000)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reserveWithLock(Long productId, int quantity) {
        // 1) Redis 캐시 체크 (빠른 실패)
        Integer cachedStock = (Integer) redisTemplate.opsForValue()
            .get("product:" + productId + ":stock");

        if (cachedStock != null && cachedStock < quantity) {
            throw new InsufficientStockException();
        }

        // 2) DB 재고 차감
        InventoryEntity inv = inventoryJpa.findById(productId)
            .orElseThrow(() -> new ProductNotFoundException());

        inv.decreaseStock(quantity);
        inventoryJpa.save(inv);

        // 3) Redis 캐시 동기화
        redisTemplate.opsForValue().decrement("product:" + productId + ":stock", quantity);
    }
}
```

### 4.4 락 타임아웃 설정 가이드

| 락 종류 | 권장 Lease Time | 권장 Wait Time | 이유 |
|---------|----------------|----------------|------|
| **쿠폰 발급** | 5초 | 3초 | 빠른 처리 필요 |
| **재고 예약** | 10초 | 2초 | DB 업데이트 시간 고려 |
| **결제 처리** | 30초 | 5초 | 외부 API 호출 가능성 |

```java
// Lease Time > DB Tx Timeout 보장
spring:
  transaction:
    default-timeout: 10  # 10초

@DistributedLock(
    key = "...",
    leaseTime = 15000,  // 15초 (DB Tx보다 길게)
    waitTime = 3000     // 3초
)
```

### 4.5 장애 대응 전략

#### Redis 장애 시나리오

```java
@Service
public class ResilientCouponService {

    @DistributedLock(key = "'coupon:' + #couponId + ':lock'")
    public Result issue(Command cmd) {
        try {
            // Redis Atomic Counter 시도
            Long count = redisTemplate.opsForValue()
                .increment("coupon:" + cmd.couponId() + ":issued");

            if (count > maxIssuance) {
                throw new CouponExhaustedException();
            }
        } catch (RedisConnectionException e) {
            // Redis 장애 시 DB Fallback
            log.warn("Redis 연결 실패, DB로 폴백: {}", e.getMessage());

            // DB Pessimistic Lock으로 폴백
            Coupon coupon = couponPort.findByIdWithLock(cmd.couponId())
                .orElseThrow(() -> new CouponNotFoundException());

            if (!coupon.tryIncrementIssuedCount()) {
                throw new CouponExhaustedException();
            }
        }

        // 발급 처리
        couponPort.saveIssuance(cmd.userId(), cmd.couponId());

        return new Result(cmd.couponId(), cmd.userId());
    }
}
```

---

## 5. 구현 계획

### 5.1 Phase 1: 분산락 인프라 구축 (1-2일)

#### 5.1.1 Redisson 의존성 추가

```gradle
// build.gradle
dependencies {
    implementation 'org.redisson:redisson-spring-boot-starter:3.25.0'
}
```

#### 5.1.2 Redisson 설정

```java
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
            .setAddress("redis://" + redisHost + ":" + redisPort)
            .setConnectionPoolSize(50)
            .setConnectionMinimumIdleSize(10)
            .setIdleConnectionTimeout(10000)
            .setConnectTimeout(3000)
            .setTimeout(3000)
            .setRetryAttempts(3)
            .setRetryInterval(1500);

        return Redisson.create(config);
    }
}
```

#### 5.1.3 @DistributedLock 애노테이션 작성

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {
    String key();
    long leaseTime() default 5000L;
    long waitTime() default 3000L;
    String failMessage() default "리소스 잠금 획득 실패";
}
```

#### 5.1.4 AOP 구현

```java
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class DistributedLockAspect {

    private final RedissonClient redissonClient;
    private final SpelExpressionParser parser = new SpelExpressionParser();

    @Around("@annotation(distributedLock)")
    public Object lock(ProceedingJoinPoint joinPoint, DistributedLock distributedLock)
            throws Throwable {

        String lockKey = parseKey(distributedLock.key(), joinPoint);
        RLock lock = redissonClient.getLock(lockKey);

        log.debug("분산락 획득 시도: key={}", lockKey);

        boolean acquired = lock.tryLock(
            distributedLock.waitTime(),
            distributedLock.leaseTime(),
            TimeUnit.MILLISECONDS
        );

        if (!acquired) {
            log.warn("분산락 획득 실패: key={}", lockKey);
            throw new LockAcquisitionException(distributedLock.failMessage());
        }

        log.debug("분산락 획득 성공: key={}", lockKey);

        try {
            return joinPoint.proceed();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("분산락 해제: key={}", lockKey);
            }
        }
    }

    private String parseKey(String keyExpression, ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        EvaluationContext context = new StandardEvaluationContext();

        String[] paramNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < paramNames.length; i++) {
            context.setVariable(paramNames[i], args[i]);
        }

        Expression expression = parser.parseExpression(keyExpression);
        return expression.getValue(context, String.class);
    }
}
```

#### 5.1.5 통합 테스트 작성

```java
@SpringBootTest
@Testcontainers
class DistributedLockIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.2")
        .withExposedPorts(6379);

    @Autowired
    private RedissonClient redissonClient;

    @Test
    void 분산락_동시_획득_테스트() throws Exception {
        // Given
        String lockKey = "test:lock";
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // When
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    RLock lock = redissonClient.getLock(lockKey);
                    boolean acquired = lock.tryLock(100, 1000, TimeUnit.MILLISECONDS);

                    if (acquired) {
                        try {
                            successCount.incrementAndGet();
                            Thread.sleep(10);
                        } finally {
                            lock.unlock();
                        }
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Then
        assertThat(successCount.get()).isGreaterThan(0);
        assertThat(successCount.get() + failCount.get()).isEqualTo(threadCount);
    }
}
```

### 5.2 Phase 2: 쿠폰 발급에 분산락 적용 (1일)

#### 구현 체크리스트
- [ ] Redis Atomic Counter 구현
- [ ] `CouponService.issue()` 분산락 적용
- [ ] 기존 Pessimistic Lock 제거
- [ ] 중복 발급 방지 로직 개선
- [ ] 100 스레드 동시 발급 테스트

### 5.3 Phase 3: 주문/재고에 분산락 적용 (1-2일)

#### 구현 체크리스트
- [ ] `InventoryService.reserveWithLock()` 메서드 추가
- [ ] 재고 캐시 (Cache-Aside) 구현
- [ ] `OrderService.create()` 리팩토링
- [ ] DB Tx 범위 조정
- [ ] 재고 1개, 100 스레드 테스트

### 5.4 Phase 4: 캐싱 전략 구현 (1-2일)

#### 구현 체크리스트
- [ ] 쿠폰 정보 캐싱 + 무효화
- [ ] 재고 조회 캐싱 (Cache-Aside)
- [ ] 주문 내역 캐싱
- [ ] Redis 메모리 사용량 모니터링
- [ ] 캐시 히트율 측정

### 5.5 Phase 5: 성능 테스트 & 보고서 (1일)

#### 테스트 항목
- [ ] 쿠폰 발급 부하 테스트 (1000 TPS)
- [ ] 재고 예약 동시성 테스트
- [ ] 캐시 적용 전/후 쿼리 수 비교
- [ ] 응답 시간 개선 측정
- [ ] 보고서 작성 (`performance-test-results.md`)

---

## 6. 성능 목표

### 6.1 처리량(TPS) 목표

| 기능 | Before (DB Lock) | After (분산락) | 개선율 |
|------|-----------------|---------------|--------|
| **쿠폰 발급** | 100 TPS | 5,000 TPS | **50배** |
| **재고 예약** | 500 TPS | 2,000 TPS | **4배** |
| **중복 결제 방지** | 200 TPS | 1,000 TPS | **5배** |

### 6.2 응답 시간 목표

| 기능 | p50 | p95 | p99 |
|------|-----|-----|-----|
| **쿠폰 발급** | < 50ms | < 100ms | < 200ms |
| **재고 예약** | < 100ms | < 200ms | < 500ms |
| **상품 조회 (캐시)** | < 10ms | < 20ms | < 50ms |

### 6.3 캐시 효율성 목표

| 캐시 대상 | 목표 Hit Rate | 메모리 사용량 |
|----------|--------------|-------------|
| **상품 정보** | > 90% | < 100MB |
| **쿠폰 정보** | > 80% | < 50MB |
| **주문 내역** | > 70% | < 200MB |

### 6.4 동시성 정합성 목표

| 테스트 시나리오 | 정합성 요구 | 측정 방법 |
|---------------|-----------|-----------|
| **쿠폰 선착순 100명** | 정확히 100명 발급 | 멀티스레드 테스트 |
| **재고 1개, 100명 주문** | 정확히 1명 성공 | 멀티스레드 테스트 |
| **동시 결제** | 음수 잔액 0건 | 멀티스레드 테스트 |

---

## 7. 예상 이슈 및 대응 방안

### 7.1 Redis 장애 시나리오

**문제**: Redis 다운 시 분산락 전체 불가

**해결책**:
```java
@Service
public class ResilientLockService {

    @DistributedLock(key = "...")
    public Result process(Command cmd) {
        try {
            // Redis 분산락 로직
        } catch (RedisConnectionException e) {
            log.error("Redis 연결 실패, DB Lock으로 폴백", e);
            // DB Pessimistic Lock으로 폴백
            return processWithDbLock(cmd);
        }
    }
}
```

### 7.2 분산락 데드락 위험

**문제**: 여러 자원을 동시에 Lock 할 때 데드락

**해결책**:
- Lock 순서 일관성 유지 (항상 resourceId 오름차순)
- MultiLock 사용 (Redisson 제공)
- Timeout 설정으로 무한 대기 방지

```java
RLock lock1 = redissonClient.getLock("resource:1");
RLock lock2 = redissonClient.getLock("resource:2");

// MultiLock으로 원자적 획득
RLock multiLock = redissonClient.getMultiLock(lock1, lock2);
multiLock.tryLock(3000, 5000, TimeUnit.MILLISECONDS);
```

### 7.3 캐시 불일치 문제

**문제**: Redis 캐시와 DB 데이터 불일치

**해결책**:
1. 짧은 TTL 설정 (10~30초)
2. Write-Through 패턴 적용
3. 주기적 동기화 배치
4. 중요 데이터는 항상 DB 조회

---

## 8. 참고 자료

- [Redisson Documentation](https://github.com/redisson/redisson/wiki/Table-of-Content)
- [Spring Cache Abstraction](https://docs.spring.io/spring-framework/reference/integration/cache.html)
- [Redis Best Practices](https://redis.io/docs/manual/patterns/)
- [Distributed Locks with Redis](https://redis.io/topics/distlock)

---

**문서 작성일**: 2025-11-05
**작성자**: Claude Code
**버전**: 1.0.0
