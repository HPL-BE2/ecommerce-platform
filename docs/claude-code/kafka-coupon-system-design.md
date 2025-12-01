# Kafka 기반 쿠폰 발급 시스템 개선 설계

> 대용량 트래픽 대응을 위한 쿠폰 발급 시스템 Kafka 전환 설계 문서

## 📚 목차
1. [현재 시스템 분석](#1-현재-시스템-분석)
2. [문제점 및 개선 방향](#2-문제점-및-개선-방향)
3. [Kafka 적용 이유](#3-kafka-적용-이유)
4. [목표 아키텍처](#4-목표-아키텍처)
5. [비즈니스 시퀀스 다이어그램](#5-비즈니스-시퀀스-다이어그램)
6. [Kafka 구성](#6-kafka-구성)
7. [구현 계획](#7-구현-계획)
8. [예상 효과](#8-예상-효과)

---

## 1. 현재 시스템 분석

### 1.1 AS-IS 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                    Coupon API                               │
│                                                              │
│  [동기 발급] POST /coupons/{id}/issue                       │
│       │                                                      │
│       ├─ Redis 분산락 획득 (대기)                           │
│       ├─ 쿠폰 재고 확인                                      │
│       ├─ DB 저장                                             │
│       └─ 응답 반환 (500ms ~ 1s)                             │
│                                                              │
│  [비동기 발급] POST /coupons/{id}/issue-async               │
│       │                                                      │
│       ├─ Lua Script (Redis 검증)                            │
│       ├─ Spring Application Event 발행                      │
│       ├─ 즉시 응답 (50ms)                                   │
│       │                                                      │
│       └──> @Async EventListener                             │
│              └─ DB 저장 (비동기)                            │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 현재 구현 분석

#### **동기 발급 API** (`/coupons/{id}/issue`)

**로직:**
```java
@DistributedLock(key = "'coupon:' + #cmd.couponId() + ':lock'")
@Transactional
public Result issue(Command cmd) {
    // 1. 쿠폰 조회
    // 2. 유효성 검증
    // 3. 중복 발급 확인
    // 4. Redis Atomic Counter 증가
    // 5. DB 저장
    return result;
}
```

**문제점:**
- ❌ Redis 분산락 획득 대기 시간 (최대 3초)
- ❌ 동기 처리로 응답 지연 (평균 500ms ~ 1초)
- ❌ 선착순 이벤트 시 DB 부하 집중
- ❌ 처리량 제한 (약 1,000 req/sec)

#### **비동기 발급 API** (`/coupons/{id}/issue-async`)

**로직:**
```java
// 1. Lua Script로 Redis 검증 (원자적)
boolean reserved = luaScript.execute(
    couponId, userId, maxIssuance
);

// 2. Spring Application Event 발행
eventPublisher.publishEvent(
    new CouponIssueMessage(requestId, couponId, userId)
);

// 3. 즉시 응답 (202 Accepted)
return new Result(requestId, "발급 요청 접수");

// 4. @Async Listener가 DB 저장
@Async
@EventListener
public void handle(CouponIssueMessage message) {
    couponPort.issueCoupon(couponId, userId);
}
```

**장점:**
- ✅ 빠른 응답 (50ms 이내)
- ✅ 비동기 처리로 사용자 경험 개선

**문제점:**
- ❌ Spring Application Event는 단일 JVM 내에서만 동작
- ❌ 다중 인스턴스 배포 시 이벤트 유실 가능
- ❌ 실패 시 재시도 메커니즘 부족
- ❌ 처리 이력 추적 어려움
- ❌ 확장성 제한

---

## 2. 문제점 및 개선 방향

### 2.1 대용량 트래픽 발생 시나리오

```
선착순 쿠폰 이벤트 (10,000장 한정)
  - 동시 접속: 50,000명
  - 시간: 10초 이내
  - 초당 요청: 5,000 req/sec

현재 시스템:
  ❌ 동기 방식: 1,000 req/sec 처리 (80% 실패)
  ❌ 비동기 방식: 단일 JVM 제약 (다중 인스턴스 배포 불가)
  ❌ DB 부하: 동시 INSERT로 락 경합 발생
```

### 2.2 개선 방향

#### **핵심 목표**
1. **응답 시간 단축**: 50ms 이내 즉시 응답
2. **처리량 증대**: 10,000 req/sec 이상 처리
3. **확장성 확보**: 다중 인스턴스 배포 가능
4. **안정성 향상**: 자동 재시도 및 실패 처리

#### **해결 방안**
- Kafka를 활용한 이벤트 기반 비동기 처리
- 파티셔닝을 통한 병렬 처리
- Consumer Group을 통한 수평 확장
- Transactional Outbox Pattern 적용 (선택)

---

## 3. Kafka 적용 이유

### 3.1 왜 Kafka를 선택했는가?

#### **1) 높은 처리량 (High Throughput)**
```
Spring Application Event:
  - 단일 JVM 내 비동기 처리
  - 초당 10,000 msg 처리 가능
  - 하지만 다중 인스턴스 배포 불가

Kafka:
  - 분산 메시징 시스템
  - 초당 수백만 msg 처리 가능
  - 파티셔닝으로 병렬 처리
```

#### **2) 확장성 (Scalability)**
```
파티션 10개 + Consumer 10개
  → 각 Consumer가 1,000 req/sec 처리
  → 총 10,000 req/sec 처리 가능

Consumer 추가만으로 처리량 증대 가능
```

#### **3) 내구성 (Durability)**
```
Kafka:
  - 디스크에 메시지 저장
  - Replication으로 데이터 보호
  - Consumer 장애 시에도 메시지 유지

Spring Event:
  - 메모리에만 존재
  - 인스턴스 재시작 시 이벤트 유실
```

#### **4) 재처리 가능 (Reprocessability)**
```
Kafka:
  - Offset 관리로 재처리 가능
  - Consumer 장애 복구 후 이어서 처리

Spring Event:
  - 한 번 처리하면 끝
  - 재처리 불가
```

#### **5) 모니터링 용이**
```
Kafka:
  - Consumer Lag 모니터링
  - 처리량, 지연시간 추적
  - 운영 도구 풍부 (Kafka Manager, Grafana)

Spring Event:
  - 별도 모니터링 구현 필요
```

### 3.2 Kafka가 적합한 이유

#### **쿠폰 발급 시스템의 특성**
1. **대량 트래픽**: 선착순 이벤트 시 순간 트래픽 폭증
2. **순차 처리 필요**: 같은 쿠폰은 순서대로 처리 (재고 관리)
3. **확장 필요**: 이벤트 규모에 따라 동적 확장
4. **안정성 중요**: 쿠폰 발급 실패는 비즈니스 손실

#### **Kafka의 장점 활용**
- ✅ 파티셔닝 → 같은 쿠폰은 같은 파티션 (순서 보장)
- ✅ Consumer Group → 병렬 처리 (처리량 증대)
- ✅ Replication → 데이터 손실 방지
- ✅ Offset 관리 → 재처리 및 복구

---

## 4. 목표 아키텍처

### 4.1 TO-BE 아키텍처

```
┌─────────────────────────────────────────────────────────────────┐
│                       Coupon API                                 │
│                                                                  │
│  [Kafka 비동기 발급] POST /coupons/{id}/issue                   │
│       │                                                          │
│       ├─ Lua Script (Redis 검증)                                │
│       ├─ Kafka Producer (발급 요청)                             │
│       └─ 즉시 응답 202 Accepted (50ms 이내)                    │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│                    Kafka Cluster                                 │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ Topic: coupon.issue.requests                               │ │
│  │  - Partitions: 10                                          │ │
│  │  - Replication Factor: 2                                   │ │
│  │  - Retention: 1 day                                        │ │
│  │  - Key: couponId                                           │ │
│  └────────────────────────────────────────────────────────────┘ │
└───────────┬──────────────────────────────────────────────────────┘
            │
    ┌───────▼────┐
    │ Consumer   │
    │ Group:     │
    │ coupon-    │
    │ issue-     │
    │ processor  │
    │            │
    │ 동시성: 10 │
    └─────┬──────┘
          │
          ▼
    ┌─────────────┐
    │ DB 저장     │
    │ (쿠폰 발급) │
    └─────┬───────┘
          │
          ▼
┌──────────────────────────────────────────────────────────────────┐
│                    Kafka Cluster                                 │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ Topic: coupon.issue.results                                │ │
│  │  - Partitions: 5                                           │ │
│  │  - Replication Factor: 2                                   │ │
│  │  - Retention: 3 days                                       │ │
│  │  - Key: userId                                             │ │
│  └────────────────────────────────────────────────────────────┘ │
└───────────┬──────────────────────────────────────────────────────┘
            │
    ┌───────▼────┐
    │ Consumer   │
    │ Group:     │
    │ coupon-    │
    │ notification│
    │            │
    │ 동시성: 3  │
    └─────┬──────┘
          │
          ▼
    ┌─────────────┐
    │ 알림 발송   │
    │ (이메일/푸시)│
    └─────────────┘
```

### 4.2 핵심 설계 원칙

#### **1) 파티셔닝 전략**
```java
// couponId를 Key로 사용
// → 같은 쿠폰은 같은 파티션으로
// → 순차 처리 보장

ProducerRecord<Long, String> record = new ProducerRecord<>(
    "coupon.issue.requests",
    event.couponId(),  // Key
    toJson(event)       // Value
);

// 파티션 결정
hash(couponId) % 10 = partition_number
```

**장점:**
- 같은 쿠폰의 발급 요청은 순서대로 처리
- 재고 관리 안정성 확보
- 파티션별 병렬 처리

#### **2) Consumer Group 전략**
```
Consumer Group: coupon-issue-processor
  - Concurrency: 10
  - 각 Consumer가 1개 파티션 처리
  - 파티션 10개 → Consumer 10개 병렬 처리
  - 초당 10,000 req 처리 가능
```

#### **3) 메시지 보관 전략**
```yaml
Topic: coupon.issue.requests
  Retention: 1 day
  목적: Consumer 장애 시 복구 시간 확보

Topic: coupon.issue.results
  Retention: 3 days
  목적: 사용자 문의 대응 및 히스토리 추적
```

---

## 5. 비즈니스 시퀀스 다이어그램

### 5.1 성공 시나리오

```
사용자         API          Kafka         Consumer       DB        알림
  │             │             │              │            │         │
  ├─ POST ─────>│             │              │            │         │
  │  /coupons   │             │              │            │         │
  │             ├─ Lua ───────>              │            │         │
  │             │  (Redis)    │              │            │         │
  │             │<─ OK ────────              │            │         │
  │             │             │              │            │         │
  │             ├─ Produce ──>│              │            │         │
  │             │  (request)  │              │            │         │
  │<─ 202 ──────┤             │              │            │         │
  │  Accepted   │             │              │            │         │
  │  (50ms)     │             │              │            │         │
  │             │             ├─ Consume ───>│            │         │
  │             │             │              ├─ 재고 ───>│         │
  │             │             │              │  확인     │         │
  │             │             │              │<─ OK ─────┤         │
  │             │             │              ├─ 발급 ───>│         │
  │             │             │              │<─ 완료 ───┤         │
  │             │             │<─ Produce ───┤            │         │
  │             │             │   (result)   │            │         │
  │             │             │              │            ├─ 알림 ─>│
  │<─────────────────────────────────────────────────────────────────┤
  │                        발급 완료 알림                            │
```

### 5.2 실패 시나리오 (재고 부족)

```
사용자         API          Kafka         Consumer       DB        알림
  │             │             │              │            │         │
  ├─ POST ─────>│             │              │            │         │
  │             ├─ Lua ───────>              │            │         │
  │             │  (Redis)    │              │            │         │
  │             │<─ OK ────────              │            │         │
  │             ├─ Produce ──>│              │            │         │
  │<─ 202 ──────┤             │              │            │         │
  │             │             ├─ Consume ───>│            │         │
  │             │             │              ├─ 재고 ───>│         │
  │             │             │              │  확인     │         │
  │             │             │              │<─ 없음 ───┤         │
  │             │             │<─ Produce ───┤            │         │
  │             │             │   (FAILED)   │            │         │
  │             │             │              │            ├─ 알림 ─>│
  │<─────────────────────────────────────────────────────────────────┤
  │                     재고 부족 알림                               │
```

### 5.3 Consumer 장애 시나리오

```
사용자         API          Kafka         Consumer       DB
  │             │             │              │            │
  ├─ POST ─────>│             │              │            │
  │             ├─ Produce ──>│              │            │
  │<─ 202 ──────┤             │              │            │
  │             │             │              X (장애)     │
  │             │             │              │            │
  │             │             │ (메시지 보관) │            │
  │             │             │              │            │
  │             │             │              ↓            │
  │             │             │         (재시작)          │
  │             │             ├─ Consume ───>│            │
  │             │             │   (재처리)   │            │
  │             │             │              ├─ 발급 ───>│
  │<───────────────────────────────────────────────────────
  │                     정상 발급 완료                    │
```

---

## 6. Kafka 구성

### 6.1 Topic 설계

#### **Topic 1: `coupon.issue.requests`**

```yaml
Topic Name: coupon.issue.requests
Purpose: 쿠폰 발급 요청
Partitions: 10
Replication Factor: 2
Retention: 1 day (86400000 ms)
Key: couponId (같은 쿠폰은 같은 파티션)

Config:
  min.insync.replicas: 2       # 최소 복제본
  compression.type: snappy     # 압축
  max.message.bytes: 1048576   # 최대 메시지 크기 (1MB)
```

**Message Schema:**
```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "couponId": 100,
  "userId": 12345,
  "requestedAt": "2025-12-01T10:30:00.123Z"
}
```

**파티셔닝 전략:**
```
couponId=100 → hash(100) % 10 = 0 → Partition 0
couponId=101 → hash(101) % 10 = 1 → Partition 1
couponId=102 → hash(102) % 10 = 2 → Partition 2
...

같은 couponId는 항상 같은 파티션
→ 순서 보장
```

#### **Topic 2: `coupon.issue.results`**

```yaml
Topic Name: coupon.issue.results
Purpose: 쿠폰 발급 결과
Partitions: 5
Replication Factor: 2
Retention: 3 days (259200000 ms)
Key: userId

Config:
  min.insync.replicas: 2
  compression.type: snappy
```

**Message Schema:**
```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "couponId": 100,
  "userId": 12345,
  "status": "SUCCESS",
  "issuanceId": 67890,
  "message": "쿠폰이 발급되었습니다.",
  "issuedAt": "2025-12-01T10:30:01.234Z"
}
```

**실패 응답:**
```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "couponId": 100,
  "userId": 12345,
  "status": "FAILED",
  "reason": "OUT_OF_STOCK",
  "message": "쿠폰이 모두 소진되었습니다.",
  "failedAt": "2025-12-01T10:30:01.234Z"
}
```

### 6.2 Producer 설정

```yaml
spring:
  kafka:
    producer:
      key-serializer: org.apache.kafka.common.serialization.LongSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all                      # 모든 복제본 확인
      retries: 3                     # 재시도
      properties:
        enable.idempotence: true     # 중복 방지
        compression.type: snappy     # 압축
        max.in.flight.requests.per.connection: 5
```

### 6.3 Consumer 설정

```yaml
spring:
  kafka:
    consumer:
      key-deserializer: org.apache.kafka.common.serialization.LongDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      enable-auto-commit: false      # 수동 커밋
      auto-offset-reset: earliest    # 처음부터 읽기
      max-poll-records: 100          # 한 번에 가져올 레코드
    listener:
      ack-mode: manual               # 수동 Ack
      concurrency: 10                # Consumer 수
```

### 6.4 Kafka Configuration

```java
@Configuration
@EnableKafka
public class KafkaConfig {

    @Bean
    public NewTopic couponIssueRequestsTopic() {
        return TopicBuilder.name("coupon.issue.requests")
                .partitions(10)
                .replicas(2)
                .config("retention.ms", "86400000")  // 1일
                .config("min.insync.replicas", "2")
                .build();
    }

    @Bean
    public NewTopic couponIssueResultsTopic() {
        return TopicBuilder.name("coupon.issue.results")
                .partitions(5)
                .replicas(2)
                .config("retention.ms", "259200000")  // 3일
                .config("min.insync.replicas", "2")
                .build();
    }
}
```

---

## 7. 구현 계획

### 7.1 구현 단계

#### **Phase 1: Producer 구현**
```java
// 1. CouponIssueRequestProducer
@Component
class CouponIssueKafkaProducer {
    public void publish(CouponIssueRequest request) {
        kafkaTemplate.send(
            "coupon.issue.requests",
            request.couponId(),
            toJson(request)
        );
    }
}

// 2. API 통합
@PostMapping("/{couponId}/issue")
public ResponseEntity<?> issueCoupon(...) {
    // Lua Script 검증
    // Kafka 발행
    // 즉시 응답 202
}
```

#### **Phase 2: Consumer 구현**
```java
// 1. CouponIssueConsumer
@Component
class CouponIssueKafkaConsumer {
    @KafkaListener(
        topics = "coupon.issue.requests",
        groupId = "coupon-issue-processor",
        concurrency = "10"
    )
    @Transactional
    public void consume(String payload, Acknowledgment ack) {
        // 쿠폰 발급 처리
        // 결과 발행
        // Ack
    }
}
```

#### **Phase 3: Result Consumer 구현**
```java
// 1. CouponIssueResultConsumer
@Component
class CouponIssueResultKafkaConsumer {
    @KafkaListener(
        topics = "coupon.issue.results",
        groupId = "coupon-notification"
    )
    public void consume(String payload) {
        // 알림 발송
    }
}
```

#### **Phase 4: 기존 코드 정리**
```java
// 1. 기존 Spring Event 방식 제거
// - CouponIssueMessage
// - CouponIssueMessageListener
// - InMemoryCouponIssueMessagePublisher

// 2. /issue-async API 제거 또는 /issue로 통합
```

### 7.2 마이그레이션 전략

```
현재: /coupons/{id}/issue-async (Spring Event)
      ↓
전환: /coupons/{id}/issue (Kafka)
      ↓
배포: Kafka Consumer 먼저 배포
      Kafka Producer 배포
      기존 API 제거
```

---

## 8. 예상 효과

### 8.1 성능 개선

| 항목 | AS-IS | TO-BE (Kafka) | 개선율 |
|------|-------|---------------|--------|
| 응답 시간 | 50ms | 50ms | **유지** |
| 처리량 | 1,000 req/sec (단일 인스턴스) | 10,000 req/sec (Consumer 10개) | **10배** |
| 확장성 | 제한적 (단일 JVM) | 우수 (다중 인스턴스) | **무한 확장** |
| 안정성 | 중간 (메모리 기반) | 높음 (디스크 기반) | **향상** |
| 재처리 | 불가 | 가능 (Offset 리셋) | **가능** |
| 모니터링 | 어려움 | 용이 (Kafka Lag) | **향상** |

### 8.2 비즈니스 가치

#### **1) 사용자 경험 개선**
- ✅ 즉시 응답 (50ms 이내)
- ✅ 선착순 이벤트 시 안정적 서비스
- ✅ 발급 결과 알림 (이메일/푸시)

#### **2) 시스템 안정성 향상**
- ✅ 트래픽 급증에도 견딜 수 있는 구조
- ✅ Consumer 장애 시 자동 복구
- ✅ 메시지 유실 방지

#### **3) 운영 효율성**
- ✅ Consumer Lag 모니터링으로 장애 조기 감지
- ✅ 처리 이력 추적 가능 (Kafka 메시지 보관)
- ✅ 장애 발생 시 재처리 용이

#### **4) 확장성**
- ✅ 파티션 추가로 처리량 증대
- ✅ Consumer 추가로 처리 속도 향상
- ✅ 다중 인스턴스 배포 가능

### 8.3 비용 대비 효과

```
비용:
  - Kafka 클러스터 운영 비용: +$500/월
  - 개발 및 전환 비용: 3~5일

효과:
  - 서버 증설 불필요 (수평 확장)
  - 장애 감소로 운영 비용 절감
  - 사용자 만족도 증가 (빠른 응답)
  - 비즈니스 기회 확대 (대규모 이벤트 가능)

ROI: 3개월 내 투자 회수 예상
```

---

## 9. 리스크 및 대응 방안

### 9.1 리스크

| 리스크 | 영향 | 확률 | 대응 방안 |
|--------|------|------|-----------|
| Kafka 클러스터 장애 | 높음 | 낮음 | Replication Factor 2, 모니터링 강화 |
| Consumer Lag 발생 | 중간 | 중간 | 동시성 조정, 파티션 추가 |
| 메시지 중복 처리 | 낮음 | 중간 | 멱등성 체크 (requestId) |
| 순서 보장 실패 | 높음 | 낮음 | Key 기반 파티셔닝 (couponId) |

### 9.2 롤백 계획

```
문제 발생 시:
  1. Kafka Consumer 비활성화
  2. 기존 Spring Event 방식 재활성화
  3. Kafka Offset 최신으로 이동 (메시지 스킵)
  4. 원인 분석 후 재배포
```

---

## 10. 모니터링

### 10.1 핵심 지표

```yaml
Producer:
  - kafka.producer.record-send-rate
  - kafka.producer.record-error-rate
  - kafka.producer.request-latency-avg

Consumer:
  - kafka.consumer.records-consumed-rate
  - kafka.consumer.records-lag-max  # Consumer Lag
  - kafka.consumer.fetch-latency-avg

Topic:
  - kafka.topic.messages-in-per-sec
  - kafka.topic.bytes-in-per-sec
```

### 10.2 알람 설정

```yaml
Critical:
  - Consumer Lag > 10,000
  - Consumer Down
  - Producer Error Rate > 5%

Warning:
  - Consumer Lag > 1,000
  - Producer Latency > 1s
```

---

## 11. 결론

### 11.1 요약

이번 Kafka 전환을 통해:
- ✅ 대용량 트래픽 대응 가능 (10,000 req/sec)
- ✅ 확장 가능한 아키텍처 구축
- ✅ 시스템 안정성 향상
- ✅ 운영 효율성 증대

### 11.2 다음 단계

1. **단기 (1주)**
   - Producer/Consumer 구현
   - 통합 테스트
   - 성능 테스트

2. **중기 (1개월)**
   - 프로덕션 배포
   - 모니터링 대시보드 구축
   - 기존 코드 정리

3. **장기 (3개월)**
   - 다른 기능으로 확장 (재고 예약 등)
   - Kafka Streams 활용 (실시간 집계)
   - Schema Registry 도입

---

**설계 완료! 이제 구현을 시작합니다!** 🚀
