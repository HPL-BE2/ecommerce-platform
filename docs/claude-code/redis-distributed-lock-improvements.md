# Redis 분산락 & 캐싱 시스템 개선 보고서

> **작성일**: 2025-11-11
> **브랜치**: `claude/redis-distributed-lock-improvements-011CV1X7xHPoBZsrp3eusuXs`

## 📋 개요

본 문서는 코드 리뷰에서 지적된 Redis 분산락 및 캐싱 시스템의 개선 사항을 정리한 보고서입니다.
기존 구현의 잠재적 문제점을 분석하고, 7가지 핵심 개선 사항을 적용하여 시스템의 안정성과 성능을 향상시켰습니다.

---

## 🎯 개선 목표

1. **기능 정상화**: Redis INCR/DECR 명령 실행 가능하도록 Serializer 수정
2. **데이터 일관성**: DB-Redis 동기화 시점 개선 및 롤백 예외 처리 강화
3. **동시성 제어**: 데드락 방지 및 Aspect 실행 순서 명시
4. **성능 최적화**: Redisson 커넥션 풀 크기 조정
5. **디버깅 편의성**: 예외 로깅 강화

---

## 🔴 1. Redis Serializer 문제 수정 (**치명적 버그 수정**)

### 문제점

```java
// CacheConfig.java
template.setValueSerializer(new GenericJackson2JsonRedisSerializer());

// RedisCounterInitializer.java
redisTemplate.opsForValue().set(countKey, issuedCount);  // Long을 JSON으로 저장
// 결과: {"@class":"java.lang.Long","@value":0}

// CouponService.java
Long currentCount = redisTemplate.opsForValue().increment(countKey);
// ❌ ERR value is not an integer or out of range
```

**원인**: GenericJackson2JsonRedisSerializer가 Long 타입을 JSON 객체로 직렬화하여 Redis INCR/DECR 명령이 실패

### 해결 방안

**카운터 전용 RedisTemplate 생성 (StringRedisSerializer 사용)**

```java
// CacheConfig.java
@Bean
public RedisTemplate<String, String> counterRedisTemplate(LettuceConnectionFactory connectionFactory) {
    RedisTemplate<String, String> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(new StringRedisSerializer());  // ✅ String으로 숫자 저장
    return template;
}
```

**적용 클래스**:
- `RedisCounterInitializer`: 카운터 초기화 시 String으로 저장
- `CouponService`: increment/decrement 시 counterRedisTemplate 사용

### 영향도

- **Before**: Redis INCR/DECR 명령 실패로 기능 동작 불가
- **After**: 정상 동작 (원자적 카운터 증감 가능)

### 커밋

```
fix: Redis Serializer 문제 수정 - 카운터 전용 RedisTemplate 추가
commit: 9db39e1
```

---

## 🟠 2. 재고 캐시 동기화 시점 개선

### 문제점

```java
// InventoryService.java
inventoryPort.reserve(productId, quantity, orderId);  // DB 차감 (트랜잭션 진행 중)

if (cachedStock != null) {
    redisTemplate.opsForValue().decrement(stockKey, quantity);  // ❌ 커밋 전 캐시 갱신
}
```

| 시점 | DB | Redis Cache |
|------|----|----|
| T1 | stock = 10 | stock = 10 |
| T2 | UPDATE stock = 9 | decrement → 9 |
| T3 | **ROLLBACK!** | (그대로 9) |
| T4 | stock = 10 (복원) | stock = 9 (**불일치!**) |

**문제**: DB 트랜잭션 롤백 시 Redis 캐시만 차감된 상태로 남음

### 해결 방안

**TransactionSynchronization.afterCommit() 사용**

```java
// InventoryService.java
@Transactional
public void reserveWithLock(Long productId, int quantity, Long orderId) {
    inventoryPort.reserve(productId, quantity, orderId);

    // ✅ 트랜잭션 커밋 후 캐시 갱신
    if (cachedStock != null) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        Long newStock = redisTemplate.opsForValue().decrement(stockKey, quantity);
                        log.debug("재고 캐시 동기화 (트랜잭션 커밋 후): productId={}, newStock={}",
                                productId, newStock);
                    } catch (Exception e) {
                        // 캐시 갱신 실패는 치명적이지 않음 (TTL로 자동 동기화)
                        log.warn("재고 캐시 갱신 실패 (TTL로 복구 예정): productId={}", productId, e);
                    }
                }
            }
        );
    }
}
```

### 영향도

- **Before**: DB 롤백 시 Redis 캐시 불일치 (TTL 만료 전까지 부정확한 재고 표시)
- **After**: 트랜잭션 커밋 성공 시에만 캐시 갱신 → 일관성 보장

### 커밋

```
fix: 재고 캐시 동기화 시점 개선 - TransactionSynchronization 적용
commit: a7ae6e4
```

---

## 🟠 3. Redis 카운터 롤백 전략 개선

### 문제점

```java
// CouponService.java
if (currentCount > coupon.maxIssuance()) {
    redisTemplate.opsForValue().decrement(countKey);  // ❌ decrement 실패 시?
    throw new IllegalStateException("쿠폰이 모두 소진되었습니다");
}
```

**문제**: 네트워크 장애 등으로 decrement 자체가 실패하면 Redis 카운터만 증가한 채로 남음

### 해결 방안

**롤백 실패 시 예외 처리 및 로깅 강화**

```java
// CouponService.java
if (currentCount > coupon.maxIssuance()) {
    try {
        counterRedisTemplate.opsForValue().decrement(countKey);
        log.debug("Redis 카운터 롤백 성공: couponId={}", cmd.couponId());
    } catch (RedisConnectionFailureException e) {
        // ✅ 네트워크 장애 시 로그 + 알림 (추후 배치 동기화)
        log.error("Redis 카운터 롤백 실패 (네트워크 장애): couponId={}, countKey={}, " +
                "조치: 배치 동기화 필요", cmd.couponId(), countKey, e);
        // TODO: 알림 발송 or 동기화 큐 적재
    } catch (Exception e) {
        log.error("Redis 카운터 롤백 실패 (예상치 못한 오류): couponId={}", cmd.couponId(), e);
    }

    throw new IllegalStateException("쿠폰이 모두 소진되었습니다");
}
```

**적용 위치**:
1. 최대 발급량 초과 시 롤백
2. DataIntegrityViolationException 발생 시 롤백

### 영향도

- **Before**: 롤백 실패 시 Redis 카운터 불일치, 원인 파악 어려움
- **After**: 롤백 실패 시 ERROR 로그 + TODO 알림, 배치 동기화로 복구 가능

### 커밋

```
fix: Redis 카운터 롤백 전략 개선 - 예외 처리 강화
commit: 85b86d6
```

---

## 🟠 4. 데드락 방지 - 상품 ID 정렬로 락 순서 통일

### 문제점

**여러 상품 주문 시 다른 순서로 락 획득하면 데드락 발생**

| 시간 | Thread A (User A) | Thread B (User B) |
|------|-------------------|-------------------|
| T1 | Lock(product:1) ✓ | |
| T2 | | Lock(product:2) ✓ |
| T3 | Lock(product:2) **대기** | |
| T4 | | Lock(product:1) **대기** |
| **결과** | **Deadlock!** (waitTime 초과 후 실패) | |

**시나리오**:
- User A: [상품1, 상품2] 주문
- User B: [상품2, 상품1] 주문

### 해결 방안

**productId 오름차순 정렬로 락 획득 순서 통일**

```java
// OrderService.java
// [데드락 방지] productId 오름차순으로 정렬하여 락 획득 순서 통일
var sortedItems = items.stream()
        .sorted((a, b) -> Long.compare(a.productId(), b.productId()))
        .toList();

for (var it : sortedItems) {
    inventoryService.reserveWithLock(it.productId(), it.qty(), null);
}
```

**결과**:
- User A: [상품1, 상품2] → 정렬 후 [상품1, 상품2]
- User B: [상품2, 상품1] → 정렬 후 [상품1, 상품2]
- **모든 요청이 동일한 순서로 락 획득** → 데드락 방지

### 영향도

- **Before**: 다중 상품 주문 시 데드락 위험 (특정 상황에서 발생)
- **After**: 락 획득 순서 통일로 데드락 원천 차단

### 커밋

```
fix: 데드락 방지 - 상품 ID 정렬로 락 순서 통일
commit: d811f25
```

---

## 🟡 5. Redisson 설정 최적화 - ConnectionPoolSize 축소

### 문제점

```java
// CacheConfig.java
config.useSingleServer()
        .setConnectionPoolSize(50)          // ❌ 과도함 (DB 커넥션 풀 3개)
        .setConnectionMinimumIdleSize(10);
```

**문제**: DB 커넥션 풀이 3개인데 Redis 커넥션 풀을 50개로 설정한 것은 과도함

### 해결 방안

**ConnectionPoolSize 20으로 축소 (DB 커넥션 풀의 약 5~10배 권장)**

```java
// CacheConfig.java
/**
 * Connection Pool 설정:
 * - ConnectionPoolSize: 20 (DB 커넥션 풀의 약 5~10배 권장)
 * - MinimumIdleSize: 5 (유휴 커넥션 최소 유지)
 * <p>
 * Redisson은 Netty 기반 비동기 처리이지만, 과도한 풀 크기는 오히려
 * Redis 서버 부하와 메모리 사용량을 증가시킵니다.
 */
config.useSingleServer()
        .setConnectionPoolSize(20)          // 50 → 20
        .setConnectionMinimumIdleSize(5)     // 10 → 5
        .setConnectTimeout(3000)
        .setTimeout(3000)
        .setRetryAttempts(3)
        .setRetryInterval(1500);
```

### 영향도

- **Before**: Redis 서버 부하 증가, 메모리 사용량 과다
- **After**: 적정 수준의 커넥션 풀로 리소스 절약

### 커밋

```
perf: Redisson 설정 최적화 - ConnectionPoolSize 축소
commit: 4497e86
```

---

## 🟡 6. Aspect 순서 명시 - @Order 애노테이션 추가

### 문제점

**@DistributedLock과 @Transactional의 실행 순서가 암묵적**

Spring AOP에서 @Transactional은 기본적으로 `Ordered.LOWEST_PRECEDENCE`이므로
분산락이 먼저 실행되지만, 명시적이지 않아 유지보수 시 혼란 가능

### 해결 방안

**@Order 애노테이션으로 실행 순서 명시**

```java
// DistributedLockAspect.java
/**
 * Aspect 실행 순서:
 * - @Order(Ordered.LOWEST_PRECEDENCE - 1)로 설정하여
 *   @Transactional(LOWEST_PRECEDENCE)보다 먼저 실행
 * - 즉, 분산락 획득 → 트랜잭션 시작 → 비즈니스 로직 → 트랜잭션 커밋 → 락 해제
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 1)  // ✅ @Transactional보다 먼저 실행
public class DistributedLockAspect {
```

**실행 순서**:
1. 분산락 획득 (DistributedLockAspect)
2. 트랜잭션 시작 (@Transactional)
3. 비즈니스 로직 실행
4. 트랜잭션 커밋
5. 분산락 해제

### 영향도

- **Before**: 암묵적 순서 (기능은 정상 동작하나 명확하지 않음)
- **After**: 명시적 순서 보장 (다른 Aspect 추가 시에도 안전)

### 커밋

```
refactor: Aspect 순서 명시 - @Order 애노테이션 추가
commit: 04a2d91
```

---

## 🟡 7. 예외 로깅 개선 - 비즈니스 로직 예외 구분

### 문제점

```java
// DistributedLockAspect.java
return joinPoint.proceed();  // ❌ 예외 발생 시 락 획득 실패인지, 비즈니스 실패인지 구분 어려움
```

**문제**: 비즈니스 로직 실행 중 발생한 예외와 락 획득 실패를 구분하기 어려워 디버깅 곤란

### 해결 방안

**비즈니스 로직 예외 별도 로깅**

```java
// DistributedLockAspect.java
try {
    return joinPoint.proceed();
} catch (Exception e) {
    // ✅ 비즈니스 로직 실행 중 발생한 예외 로깅 (디버깅 편의성 향상)
    log.error("[DistributedLock] 비즈니스 로직 실행 실패: key={}, method={}, errorType={}",
            lockKey, joinPoint.getSignature().getName(), e.getClass().getSimpleName(), e);
    throw e;
}
```

**로그 예시**:
- **락 획득 실패**: `[DistributedLock] 락 획득 실패: key=coupon:1:lock, waitTime=3000ms 초과`
- **비즈니스 실패**: `[DistributedLock] 비즈니스 로직 실행 실패: key=coupon:1:lock, method=issue, errorType=IllegalStateException`

### 영향도

- **Before**: 락 관련 문제인지, 비즈니스 로직 문제인지 구분 어려움
- **After**: 예외 타입, 메서드명, 락 키를 함께 로깅하여 빠른 원인 파악 가능

### 커밋

```
refactor: 예외 로깅 개선 - 비즈니스 로직 예외 구분
commit: 69735a8
```

---

## 📊 개선 사항 요약

| 순위 | 개선 항목 | 영향도 | 커밋 해시 |
|------|----------|--------|-----------|
| 🔴 **1순위** | Redis Serializer 문제 수정 | **치명적** (기능 동작 불가 → 정상 동작) | `9db39e1` |
| 🟠 **2순위** | 재고 캐시 동기화 시점 개선 | 높음 (데이터 불일치 방지) | `a7ae6e4` |
| 🟠 **3순위** | Redis 카운터 롤백 전략 개선 | 높음 (데이터 불일치 위험 감소) | `85b86d6` |
| 🟠 **4순위** | 데드락 방지 | 중간 (다중 상품 주문 시 안정성 향상) | `d811f25` |
| 🟡 **5순위** | Redisson 설정 최적화 | 낮음 (성능 최적화) | `4497e86` |
| 🟡 **6순위** | Aspect 순서 명시 | 낮음 (명시성 향상) | `04a2d91` |
| 🟡 **7순위** | 예외 로깅 개선 | 낮음 (디버깅 편의성) | `69735a8` |

---

## 🔄 변경된 파일 목록

1. **CacheConfig.java**
   - counterRedisTemplate Bean 추가 (StringRedisSerializer)
   - Redisson ConnectionPoolSize 축소 (50 → 20)

2. **RedisCounterInitializer.java**
   - 카운터 초기화 시 counterRedisTemplate 사용

3. **CouponService.java**
   - increment/decrement 시 counterRedisTemplate 사용
   - 롤백 실패 시 예외 처리 강화

4. **InventoryService.java**
   - TransactionSynchronization으로 커밋 후 캐시 갱신

5. **OrderService.java**
   - productId 정렬로 락 획득 순서 통일

6. **DistributedLockAspect.java**
   - @Order 애노테이션 추가
   - 비즈니스 로직 예외 로깅 추가

---

## 🧪 검증 계획

### 1. Redis INCR/DECR 정상 동작 확인

```bash
# Redis CLI에서 확인
redis-cli GET coupon:1:issued
# 결과: "0" (JSON 객체가 아닌 문자열)

redis-cli INCR coupon:1:issued
# 결과: (integer) 1
```

### 2. 재고 캐시 동기화 시나리오 테스트

1. 재고 예약 요청 → DB 차감 중 예외 발생
2. 트랜잭션 롤백 확인
3. Redis 캐시가 갱신되지 않았는지 확인

### 3. 데드락 시나리오 테스트

1. 두 사용자가 동시에 [상품1, 상품2]와 [상품2, 상품1] 주문
2. 정렬 로직으로 모두 [상품1, 상품2] 순서로 처리
3. 데드락 발생하지 않고 순차 처리됨을 확인

### 4. 통합 테스트 실행

```bash
./gradlew test --tests "*IntegrationTest"
```

---

## 🚀 향후 개선 방향

### 1. Lua 스크립트 기반 원자적 카운터

현재는 INCR → 체크 → DECR 방식이지만, Lua 스크립트로 체크-증가를 원자적으로 처리 가능:

```lua
-- coupon_increment.lua
local key = KEYS[1]
local max = tonumber(ARGV[1])
local current = redis.call('GET', key)

if not current then
    current = 0
else
    current = tonumber(current)
end

if current >= max then
    return -1  -- 실패
else
    redis.call('INCR', key)
    return current + 1  -- 성공
end
```

### 2. 배치 동기화 시스템

Redis 카운터 롤백 실패 시 DB와 Redis 동기화를 자동으로 수행하는 배치 작업 구현:

- 주기적으로 DB와 Redis 카운터 비교
- 불일치 발견 시 DB 기준으로 Redis 재설정
- 알림 발송 (Slack, Email)

### 3. Redis Cluster 전환

현재 Single Server 구성이지만, 향후 트래픽 증가 시 Redis Cluster로 전환:

```java
config.useClusterServers()
        .addNodeAddress("redis://host1:6379", "redis://host2:6379")
        .setMasterConnectionPoolSize(20);
```

---

## 📚 참고 자료

- [Spring Data Redis - RedisTemplate](https://docs.spring.io/spring-data/redis/docs/current/reference/html/#redis:template)
- [Redisson - Configuration](https://github.com/redisson/redisson/wiki/2.-Configuration)
- [Spring AOP - Aspect Ordering](https://docs.spring.io/spring-framework/docs/current/reference/html/core.html#aop-ataspectj-advice-ordering)
- [Redis INCR Command](https://redis.io/commands/incr/)

---

## ✅ 결론

본 개선 작업을 통해 Redis 분산락 및 캐싱 시스템의 안정성, 성능, 유지보수성을 크게 향상시켰습니다.

특히 **Redis Serializer 문제 수정**은 시스템이 정상 동작하기 위한 필수 조치였으며,
**재고 캐시 동기화 시점 개선**과 **Redis 카운터 롤백 전략 개선**은 데이터 일관성을 보장하는 데 중요한 역할을 합니다.

향후 Lua 스크립트 도입 및 배치 동기화 시스템 구축을 통해 더욱 견고한 시스템으로 발전시킬 수 있습니다.

---

**문서 버전**: 1.0
**마지막 수정**: 2025-11-11
