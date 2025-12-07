# Kafka 통합 아키텍처 설계

> 기존 이벤트 기반 아키텍처를 Kafka로 전환하는 설계 문서

## 📚 목차
1. [현재 시스템 분석](#1-현재-시스템-분석)
2. [Kafka 통합 목표 아키텍처](#2-kafka-통합-목표-아키텍처)
3. [Topic 및 메시지 스키마 설계](#3-topic-및-메시지-스키마-설계)
4. [Producer 구조 설계](#4-producer-구조-설계)
5. [Consumer 구조 설계](#5-consumer-구조-설계)
6. [마이그레이션 전략](#6-마이그레이션-전략)
7. [확장 시나리오](#7-확장-시나리오)
8. [모니터링 및 운영](#8-모니터링-및-운영)

---

## 1. 현재 시스템 분석

### 1.1 기존 이벤트 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                    Order Service                            │
│                                                              │
│  [주문 완료] → OrderCompletedDomainEvent 발행               │
│       (Spring ApplicationEventPublisher)                     │
└─────────────────┬───────────────────────────────────────────┘
                  │
      ┌───────────┼───────────┬──────────────┐
      │           │           │              │
      ▼           ▼           ▼              ▼
┌──────────┐ ┌────────┐ ┌──────────┐   ┌─────────┐
│Ranking   │ │Analytics│ │DataPlatform│  │Future   │
│Handler   │ │Handler  │ │Handler    │  │Handlers │
│(Redis)   │ │         │ │(Outbox)   │  │         │
└──────────┘ └────────┘ └──────────┘   └─────────┘
                             │
                             ▼
                      ┌────────────┐
                      │Outbox Table│
                      └──────┬─────┘
                             │
                       ┌─────▼──────┐
                       │ Dispatcher │ (Scheduled 5초)
                       └─────┬──────┘
                             │
                             ▼
                    [External System]
                    (HTTP/Other Protocol)
```

### 1.2 현재 구조 상세 분석

#### **이벤트 흐름:**
```java
// 1. Domain Event 발행
@Service
class OrderService {
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    public void completeOrder(Order order) {
        orderRepo.save(order);

        // Spring Application Event 발행
        eventPublisher.publishEvent(
            new OrderCompletedDomainEvent(
                order.getId(),
                order.getUserId(),
                order.getTotal(),
                // ...
            )
        );
    }
}

// 2. Event Handlers (3개)
@Component
class OrderRankingEventHandler {
    @Async
    @TransactionalEventListener(phase = AFTER_COMMIT)
    public void handle(OrderCompletedDomainEvent event) {
        // Redis 랭킹 업데이트 (실시간)
        rankingService.update(event);
    }
}

@Component
class OrderInventoryAnalyticsHandler {
    @Async
    @TransactionalEventListener(phase = AFTER_COMMIT)
    public void handle(OrderCompletedDomainEvent event) {
        // 재고 분석 (향후 구현)
    }
}

@Component
class OrderDataPlatformEventHandler {
    @Async
    @TransactionalEventListener(phase = AFTER_COMMIT)
    public void handle(OrderCompletedDomainEvent event) {
        // Outbox 테이블에 저장
        outboxPublisher.publish(event);
    }
}

// 3. Outbox Pattern
@Component
class OutboxOrderEventPublisher {
    public void publish(OrderCompletedEvent event) {
        // Outbox 테이블에 PENDING 상태로 저장
        outboxRepo.save(
            new OutboxEvent(
                eventType: "ORDER_COMPLETED",
                payload: toJson(event),
                status: PENDING
            )
        );
    }
}

// 4. Outbox Dispatcher
@Component
class OutboxEventDispatcher {
    @Scheduled(fixedDelay = 5000)  // 5초마다
    public void dispatch() {
        List<OutboxEvent> events = outboxRepo.findPending();

        for (OutboxEvent event : events) {
            try {
                // 외부 시스템으로 전송 (HTTP 등)
                messageProducer.send(event);
                event.setStatus(SENT);
            } catch (Exception ex) {
                handleRetry(event, ex);
            }
        }
    }
}
```

### 1.3 현재 구조의 문제점

#### ❌ **문제 1: 단일 JVM 제약**
```
Spring Application Event는 같은 JVM 내에서만 동작

현재:
┌──────────────────┐
│  Order Service   │
│  (단일 JVM)      │
│                  │
│  ├─ Ranking      │
│  ├─ Analytics    │
│  └─ DataPlatform │
└──────────────────┘

문제점:
- 다른 마이크로서비스로 확장 불가
- 주문 서비스와 모든 핸들러가 강하게 결합
- 독립적인 배포 불가
```

#### ❌ **문제 2: Outbox 패턴의 한계**
```
Outbox Dispatcher:
  - 5초마다 폴링 (Polling)
  - 지연 시간: 평균 2.5초 (최대 5초)
  - 대량 메시지 처리 시 병목

문제점:
┌──────────────────────────────────────┐
│ Outbox Table                         │
│  - PENDING: 1,000개                  │
│  - Dispatcher Batch Size: 20개       │
└──────────────────────────────────────┘
      │
      ▼
처리 시간: 1,000 / 20 * 5초 = 250초 (4분)

대량 주문 발생 시 처리 지연
```

#### ❌ **문제 3: Consumer 추가의 어려움**
```
새로운 Consumer 추가 시:

1. 코드 변경 필요
   @Component
   class NewConsumer {
       @TransactionalEventListener
       public void handle(OrderCompletedDomainEvent event) {
           // ...
       }
   }

2. 컴파일 & 빌드

3. 전체 애플리케이션 재배포

문제점:
- 배포 없이 Consumer 추가 불가
- 기존 서비스에 영향
- 개발/배포 주기 증가
```

#### ❌ **문제 4: 데이터 재처리 불가**
```
시나리오:
  1. 랭킹 업데이트 로직에 버그 발견
  2. 버그 수정 완료
  3. 과거 7일 데이터를 다시 처리해야 함

현재 구조:
  ❌ 불가능
  - Spring Event는 일회성
  - 한 번 처리한 이벤트는 재처리 불가
  - DB에서 다시 조회하여 수동 처리 필요

이상적인 구조:
  ✅ Kafka Offset 리셋으로 재처리
  - Consumer Offset을 7일 전으로 되돌림
  - 과거 데이터부터 자동 재처리
```

#### ❌ **문제 5: 순서 보장 어려움**
```
현재:
  - @Async로 비동기 처리
  - 여러 Handler가 동시 실행
  - 실행 순서 보장 안 됨

예시:
  주문 A → 이벤트 발행
    ├─ Ranking Handler (200ms 소요)
    ├─ Analytics Handler (100ms 소요)
    └─ DataPlatform Handler (150ms 소요)

  주문 B → 이벤트 발행
    ├─ Ranking Handler (50ms 소요)
    ├─ Analytics Handler (80ms 소요)
    └─ DataPlatform Handler (120ms 소요)

결과:
  주문 A, B 순서가 Handler마다 다를 수 있음
```

---

## 2. Kafka 통합 목표 아키텍처

### 2.1 전체 아키텍처

```
┌─────────────────────────────────────────────────────────────────┐
│                       Order Service                              │
│                                                                  │
│  [주문 완료] → OrderCompletedDomainEvent                        │
│       │                                                          │
│       └──→ KafkaOrderEventHandler                               │
│              │                                                   │
│              └──→ OutboxKafkaProducer (Transactional Outbox)   │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
              ┌────────────────────────┐
              │   Outbox Table         │
              │  (이벤트 임시 저장)    │
              └────────┬───────────────┘
                       │
                 ┌─────▼──────┐
                 │ Outbox     │
                 │ Dispatcher │ (Scheduled)
                 └─────┬──────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│                    Kafka Cluster                                 │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ Topic: ecommerce.order.events                              │ │
│  │  - Partitions: 3                                           │ │
│  │  - Replication Factor: 2                                   │ │
│  │  - Retention: 7 days                                       │ │
│  │  - Key: orderId (순서 보장)                                │ │
│  └────────────────────────────────────────────────────────────┘ │
└───────────┬───────────────┬──────────────┬─────────────────────┘
            │               │              │
    ┌───────▼────┐  ┌──────▼─────┐  ┌────▼─────────┐
    │ Ranking    │  │ Analytics  │  │ Data         │
    │ Consumer   │  │ Consumer   │  │ Platform     │
    │ (Group 1)  │  │ (Group 2)  │  │ Consumer     │
    │            │  │            │  │ (Group 3)    │
    └────┬───────┘  └──────┬─────┘  └──────┬───────┘
         │                 │               │
         ▼                 ▼               ▼
    [Redis Cache]   [Analytics DB]  [Data Lake/S3]
```

### 2.2 핵심 설계 원칙

#### **원칙 1: Transactional Outbox Pattern**

```
주문 트랜잭션:
┌───────────────────────────────────────┐
│ Transaction Begin                     │
│                                       │
│  1. Order 저장                        │
│     INSERT INTO orders ...            │
│                                       │
│  2. Outbox Event 저장                 │
│     INSERT INTO outbox_events ...     │
│                                       │
│ Transaction Commit                    │
└───────────────────────────────────────┘

→ Order와 Outbox Event가 같은 트랜잭션
→ 원자성 보장 (둘 다 성공 or 둘 다 실패)

Outbox Dispatcher (별도 프로세스):
┌───────────────────────────────────────┐
│ 1. Outbox에서 PENDING 조회            │
│    SELECT * FROM outbox_events        │
│    WHERE status = 'PENDING'           │
│                                       │
│ 2. Kafka로 발행                       │
│    kafkaTemplate.send(topic, event)   │
│                                       │
│ 3. 성공 시 상태 변경                  │
│    UPDATE outbox_events               │
│    SET status = 'SENT'                │
│                                       │
│ 4. 실패 시 재시도 (Exponential Backoff)│
└───────────────────────────────────────┘

장점:
✅ 데이터 일관성 보장
✅ Kafka 장애 시에도 안전
✅ At-Least-Once 전달 보장
```

#### **원칙 2: 파티셔닝 전략**

```
Key 기반 파티셔닝:

ProducerRecord<Long, OrderCompletedEvent> record =
    new ProducerRecord<>(
        "ecommerce.order.events",  // Topic
        event.getOrderId(),         // Key ← 주문 ID
        event                       // Value
    );

파티션 결정:
  hash(orderId) % partition_count = partition_number

예시:
┌────────────────────────────────────────────────┐
│ Topic: ecommerce.order.events (3 Partitions)   │
├────────────────────────────────────────────────┤
│ Partition 0: [Order 1, 4, 7, 10, ...]         │
│ Partition 1: [Order 2, 5, 8, 11, ...]         │
│ Partition 2: [Order 3, 6, 9, 12, ...]         │
└────────────────────────────────────────────────┘

장점:
✅ 같은 주문 ID는 항상 같은 파티션
✅ 주문 단위 순서 보장
✅ 파티션별 병렬 처리
```

#### **원칙 3: Consumer Group 분리**

```
Topic: ecommerce.order.events

┌─────────────────────────────────────────────┐
│ Consumer Group: ranking-updater             │
│  - Consumer 1 → Partition 0                 │
│  - Consumer 2 → Partition 1                 │
│  - Consumer 3 → Partition 2                 │
│  - Offset: 독립 관리                        │
└─────────────────────────────────────────────┘

┌─────────────────────────────────────────────┐
│ Consumer Group: data-platform               │
│  - Consumer 1 → Partition 0, 1, 2 (모두)   │
│  - Offset: 독립 관리                        │
└─────────────────────────────────────────────┘

┌─────────────────────────────────────────────┐
│ Consumer Group: analytics                   │
│  - Consumer 1 → Partition 0, 1             │
│  - Consumer 2 → Partition 2                 │
│  - Offset: 독립 관리                        │
└─────────────────────────────────────────────┘

장점:
✅ 각 Consumer Group이 독립적
✅ 하나의 Group 장애가 다른 Group에 영향 없음
✅ 서로 다른 속도로 처리 가능
```

#### **원칙 4: 멱등성(Idempotency) 보장**

```
Producer:
  enable.idempotence: true

  동작:
    1. Producer가 각 메시지에 Sequence Number 부여
    2. Broker가 중복 감지
    3. 중복 메시지는 저장 안 함 (ACK만 응답)

Consumer:
  - eventId 기반 중복 체크
  - 처리 전 Redis/DB에서 확인

  예시:
    if (processedEvents.contains(event.getEventId())) {
        log.info("Already processed: {}", event.getEventId());
        return;  // 스킵
    }

    process(event);
    processedEvents.add(event.getEventId());

장점:
✅ 네트워크 재시도 시 안전
✅ 중복 처리 방지
✅ At-Least-Once → Exactly-Once
```

---

## 3. Topic 및 메시지 스키마 설계

### 3.1 Topic 설계

#### **Topic 1: `ecommerce.order.events`**

```yaml
Topic Name: ecommerce.order.events
Purpose: 주문 완료 이벤트 (이커머스 시스템)
Partitions: 3
Replication Factor: 2
Retention: 7 days (604800000 ms)
Key Type: Long (orderId)
Value Type: JSON (OrderCompletedEvent)

Config:
  min.insync.replicas: 2       # 최소 복제본 수
  cleanup.policy: delete       # 시간 기반 삭제
  compression.type: snappy     # 압축 (네트워크/디스크 절약)
  max.message.bytes: 1048576   # 최대 메시지 크기 (1MB)
```

**파티션 수 결정 기준:**
```
현재 요구사항:
  - 주문량: 초당 100 ~ 1,000건
  - Consumer 수: 3 ~ 6개
  - 목표 처리량: 초당 1,000건

파티션 수 = max(Consumer 수, 목표 처리량 / Consumer 처리량)
          = max(6, 1000 / 500)
          = 6

→ 여유를 두고 3개로 시작 (추후 확장 가능)
```

**Replication Factor:**
```
Replication Factor: 2

의미:
  - 각 파티션이 2개 브로커에 복제
  - Leader 1개 + Follower 1개

가용성:
  - 1개 브로커 장애까지 견딤
  - 데이터 손실 없음

트레이드오프:
  - Replication Factor ↑ → 안정성 ↑, 디스크 사용량 ↑, 성능 ↓
  - 프로덕션 권장: 3 (2개 브로커 장애까지 견딤)
  - 현재 설정: 2 (1개 브로커 장애)
```

**Retention:**
```
Retention: 7 days

목적:
  - Consumer 장애 시 복구 시간 확보
  - 데이터 재처리 가능 기간
  - 새로운 Consumer 추가 시 과거 데이터 읽기

계산:
  일일 주문량: 100,000건
  메시지 크기: 2KB (평균)
  7일 용량: 100,000 * 2KB * 7 = 1.4GB

→ 비용 대비 충분한 기간
```

---

### 3.2 메시지 스키마 설계

#### **Schema: OrderCompletedEvent**

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "eventType": "ORDER_COMPLETED",
  "eventVersion": "1.0",
  "occurredAt": "2025-12-01T10:30:00.123Z",
  "aggregateType": "order",
  "aggregateId": "12345",

  "payload": {
    "orderId": 12345,
    "userId": 67890,
    "subtotal": 50000,
    "discount": 5000,
    "total": 45000,
    "requestKey": "order-req-abc123",
    "completedAt": "2025-12-01T10:30:00.123Z",
    "items": [
      {
        "productId": 101,
        "name": "프리미엄 텀블러",
        "unitPrice": 25000,
        "quantity": 2,
        "lineTotal": 50000
      }
    ]
  },

  "metadata": {
    "producerService": "order-service",
    "producerHost": "order-pod-1",
    "producerVersion": "1.2.3",
    "traceId": "trace-abc123",
    "spanId": "span-def456"
  }
}
```

#### **필드 설명:**

| 필드 | 타입 | 필수 | 설명 | 예시 |
|------|------|------|------|------|
| `eventId` | String (UUID) | Y | 이벤트 고유 ID (멱등성 체크용) | "550e8400-..." |
| `eventType` | String | Y | 이벤트 타입 | "ORDER_COMPLETED" |
| `eventVersion` | String | Y | 스키마 버전 (호환성 관리) | "1.0" |
| `occurredAt` | String (ISO 8601) | Y | 이벤트 발생 시각 (UTC) | "2025-12-01T10:30:00.123Z" |
| `aggregateType` | String | Y | 도메인 엔티티 타입 | "order" |
| `aggregateId` | String | Y | 도메인 엔티티 ID | "12345" |
| `payload` | Object | Y | 실제 비즈니스 데이터 | {...} |
| `metadata` | Object | N | 추적/디버깅 정보 | {...} |

#### **스키마 버전 관리 전략:**

```
버전 1.0 (현재):
{
  "orderId": 12345,
  "userId": 67890,
  "total": 45000,
  "items": [...]
}

버전 1.1 (향후 - Backward Compatible):
{
  "orderId": 12345,
  "userId": 67890,
  "total": 45000,
  "items": [...],
  "shippingAddress": "서울시 강남구 ..."  ← 추가 (Optional)
}

→ 기존 Consumer도 정상 동작 (새 필드 무시)

버전 2.0 (Breaking Change):
{
  "orderId": 12345,
  "userId": 67890,
  "totalAmount": 45000,  ← 필드명 변경 (total → totalAmount)
  "items": [...],
  "currency": "KRW"      ← 새 필드 (Required)
}

→ 모든 Consumer 업데이트 필요
→ 점진적 마이그레이션:
   1. Consumer 먼저 v2 호환 코드 배포
   2. Producer v2로 변경
   3. 기존 Consumer 제거
```

---

### 3.3 Message Headers

```
Kafka Message Headers:

┌─────────────────────────────────────┐
│ Header                              │
├─────────────────────────────────────┤
│ event-id: 550e8400-e29b-41d4-...    │
│ event-type: ORDER_COMPLETED         │
│ event-version: 1.0                  │
│ content-type: application/json      │
│ trace-id: trace-abc123              │
│ span-id: span-def456                │
│ producer-service: order-service     │
│ produced-at: 2025-12-01T10:30:00Z   │
└─────────────────────────────────────┘

활용:
  - Consumer가 Body 파싱 전에 Header로 빠른 필터링
  - 분산 추적 (Distributed Tracing)
  - 메시지 라우팅
  - 스키마 버전 체크
```

---

## 4. Producer 구조 설계

### 4.1 Producer 아키텍처

```
Order Service
  │
  ├─ [1] Order Domain Logic
  │     └─ Order 저장 + Domain Event 발행
  │
  ├─ [2] KafkaOrderEventHandler (Event Listener)
  │     └─ Domain Event → Outbox Event 변환
  │
  ├─ [3] OutboxKafkaProducer
  │     └─ Outbox Table에 저장 (같은 트랜잭션)
  │
  └─ [4] OutboxKafkaDispatcher (Scheduled)
        └─ Outbox → Kafka 발행
```

### 4.2 상세 설계

#### **[1] Domain Event 발행**

```java
@Service
@RequiredArgsConstructor
class OrderService {
    private final OrderRepository orderRepo;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void completeOrder(Order order) {
        // 1. 주문 저장
        orderRepo.save(order);

        // 2. Domain Event 발행
        eventPublisher.publishEvent(
            new OrderCompletedDomainEvent(
                order.getId(),
                order.getUserId(),
                order.getSubtotal(),
                order.getDiscount(),
                order.getTotal(),
                order.getRequestKey(),
                order.getCompletedAt(),
                order.getItems().stream()
                    .map(item -> new OrderItemSnapshot(
                        item.getProductId(),
                        item.getName(),
                        item.getUnitPrice(),
                        item.getQty(),
                        item.getLineTotal()
                    ))
                    .toList()
            )
        );
    }
}
```

#### **[2] Kafka Event Handler**

```java
@Component
@RequiredArgsConstructor
@Slf4j
class KafkaOrderEventHandler {
    private final OutboxKafkaProducer outboxProducer;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(OrderCompletedDomainEvent domainEvent) {
        log.info("[Kafka] 주문 완료 이벤트 수신 orderId={}", domainEvent.orderId());

        try {
            // Domain Event → Outbox Event 변환
            OrderCompletedEvent outboxEvent = new OrderCompletedEvent(
                UUID.randomUUID().toString(),  // eventId
                "ORDER_COMPLETED",             // eventType
                "1.0",                         // eventVersion
                OffsetDateTime.now(),          // occurredAt
                "order",                       // aggregateType
                String.valueOf(domainEvent.orderId()),  // aggregateId
                new Payload(
                    domainEvent.orderId(),
                    domainEvent.userId(),
                    domainEvent.subtotal(),
                    domainEvent.discount(),
                    domainEvent.total(),
                    domainEvent.requestKey(),
                    domainEvent.completedAt(),
                    domainEvent.items()
                ),
                new Metadata(
                    "order-service",
                    InetAddress.getLocalHost().getHostName(),
                    "1.2.3",
                    MDC.get("traceId"),
                    MDC.get("spanId")
                )
            );

            // Outbox에 저장 (별도 트랜잭션)
            outboxProducer.publish(outboxEvent);

            log.info("[Kafka] Outbox 저장 완료 orderId={}", domainEvent.orderId());

        } catch (Exception e) {
            log.error("[Kafka] Outbox 저장 실패 orderId={}", domainEvent.orderId(), e);
        }
    }
}
```

#### **[3] Outbox Producer**

```java
@Component
@RequiredArgsConstructor
@Slf4j
class OutboxKafkaProducer {
    private final SpringOutboxEventJpa outboxRepo;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publish(OrderCompletedEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);

            OutboxEventEntity entity = new OutboxEventEntity();
            entity.setAggregateType("order");
            entity.setAggregateId(event.payload().orderId().toString());
            entity.setEventType("ORDER_COMPLETED");
            entity.setPayload(payload);
            entity.setStatus(OutboxEventStatus.PENDING);
            entity.setRetryCount(0);
            entity.setNextRetryAt(OffsetDateTime.now());

            outboxRepo.save(entity);

            log.debug("Outbox 이벤트 저장: id={}", entity.getId());

        } catch (JsonProcessingException e) {
            log.error("이벤트 직렬화 실패: eventId={}", event.eventId(), e);
            throw new IllegalStateException("이벤트 직렬화 실패", e);
        }
    }
}
```

#### **[4] Outbox Dispatcher**

```java
@Component
@RequiredArgsConstructor
@Slf4j
class OutboxKafkaDispatcher {
    private final SpringOutboxEventJpa outboxRepo;
    private final KafkaTemplate<Long, String> kafkaTemplate;

    @Value("${outbox.dispatcher.enabled:true}")
    private boolean enabled;

    @Value("${outbox.dispatcher.batch-size:20}")
    private int batchSize;

    @Value("${outbox.dispatcher.max-retry:5}")
    private int maxRetry;

    @Scheduled(fixedDelayString = "${outbox.dispatcher.interval-ms:5000}")
    @Transactional
    public void dispatch() {
        if (!enabled) {
            return;
        }

        List<OutboxEventEntity> events = outboxRepo
            .findByStatusAndNextRetryAtLessThanEqualOrderByIdAsc(
                OutboxEventStatus.PENDING,
                OffsetDateTime.now(),
                PageRequest.of(0, Math.max(1, batchSize))
            );

        if (events.isEmpty()) {
            return;
        }

        log.info("Outbox 이벤트 발행 시작: count={}", events.size());

        for (OutboxEventEntity event : events) {
            try {
                // Kafka 발행 (동기)
                OrderCompletedEvent parsedEvent =
                    objectMapper.readValue(event.getPayload(), OrderCompletedEvent.class);

                kafkaTemplate.send(
                    "ecommerce.order.events",
                    parsedEvent.payload().orderId(),  // Key
                    event.getPayload()                 // Value (JSON)
                ).get(10, TimeUnit.SECONDS);  // 10초 타임아웃

                // 성공 시 상태 변경
                event.setStatus(OutboxEventStatus.SENT);
                event.setSentAt(OffsetDateTime.now());
                event.setLastError(null);

                log.info("Kafka 발행 성공: id={}, orderId={}",
                    event.getId(), event.getAggregateId());

            } catch (Exception ex) {
                handleFailure(event, ex);
            }
        }
    }

    private void handleFailure(OutboxEventEntity event, Exception ex) {
        int nextRetry = event.getRetryCount() + 1;
        event.setRetryCount(nextRetry);
        event.setLastError(ex.getMessage());

        if (nextRetry >= maxRetry) {
            log.error("Outbox 이벤트 최대 재시도 초과: id={}, retries={}",
                event.getId(), nextRetry, ex);
            event.setStatus(OutboxEventStatus.FAILED);
        } else {
            event.setStatus(OutboxEventStatus.PENDING);
            long delaySeconds = (long) Math.pow(2, nextRetry) * 30;  // Exponential Backoff
            event.setNextRetryAt(OffsetDateTime.now().plusSeconds(delaySeconds));

            log.warn("Outbox 이벤트 재시도 예약: id={}, retryCount={}, nextRetry={}",
                event.getId(), nextRetry, event.getNextRetryAt());
        }
    }
}
```

### 4.3 Producer 설정

```yaml
# application.yml

spring:
  kafka:
    bootstrap-servers: localhost:9092,localhost:9093,localhost:9094
    producer:
      key-serializer: org.apache.kafka.common.serialization.LongSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all                      # 모든 복제본 확인 (안전성 최대)
      retries: 3                     # 전송 실패 시 재시도
      enable-idempotence: true       # 중복 방지
      compression-type: snappy       # 압축 (네트워크/디스크 절약)
      max-in-flight-requests-per-connection: 5
      properties:
        max.request.size: 1048576    # 최대 메시지 크기 (1MB)

outbox:
  dispatcher:
    enabled: true
    interval-ms: 5000                # 5초마다 실행
    batch-size: 20                   # 한 번에 처리할 이벤트 수
    max-retry: 5                     # 최대 재시도 횟수
    retry-delay-seconds: 30          # 기본 재시도 지연 (Exponential Backoff)
```

---

## 5. Consumer 구조 설계

### 5.1 Consumer Group 전략

```
Topic: ecommerce.order.events (3 Partitions)

┌──────────────────────────────────────────────────┐
│ Consumer Group: ranking-updater                  │
│  Purpose: 실시간 상품 랭킹 업데이트 (Redis)      │
│  Concurrency: 3                                  │
│  Offset Commit: Manual (처리 완료 후)           │
└──────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────┐
│ Consumer Group: data-platform                    │
│  Purpose: 외부 데이터 플랫폼으로 전송            │
│  Concurrency: 1 (순차 처리)                      │
│  Offset Commit: Manual                           │
└──────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────┐
│ Consumer Group: analytics                        │
│  Purpose: 재고 분석 및 트렌드 분석               │
│  Concurrency: 2                                  │
│  Offset Commit: Manual                           │
└──────────────────────────────────────────────────┘
```

### 5.2 Consumer 상세 설계

#### **Consumer 1: Ranking Updater**

```java
@Component
@RequiredArgsConstructor
@Slf4j
class RankingKafkaConsumer {
    private final ProductRankingUpdater rankingUpdater;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "ecommerce.order.events",
        groupId = "ranking-updater",
        concurrency = "3",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(
        @Payload String payload,
        @Header(KafkaHeaders.RECEIVED_KEY) Long orderId,
        @Header(KafkaHeaders.RECEIVED_PARTITION_ID) int partition,
        @Header(KafkaHeaders.OFFSET) long offset,
        Acknowledgment ack
    ) {
        log.info("[Ranking] 메시지 수신: orderId={}, partition={}, offset={}",
            orderId, partition, offset);

        try {
            // JSON 파싱
            OrderCompletedEvent event =
                objectMapper.readValue(payload, OrderCompletedEvent.class);

            // 멱등성 체크 (Optional)
            if (isAlreadyProcessed(event.eventId())) {
                log.info("[Ranking] 이미 처리된 이벤트: eventId={}", event.eventId());
                ack.acknowledge();
                return;
            }

            // 랭킹 업데이트
            for (OrderCompletedEvent.Item item : event.payload().items()) {
                rankingUpdater.incrementSales(
                    item.productId(),
                    item.quantity()
                );
            }

            // 처리 완료 후 커밋
            ack.acknowledge();

            log.info("[Ranking] 랭킹 업데이트 완료: orderId={}, items={}",
                orderId, event.payload().items().size());

        } catch (Exception e) {
            log.error("[Ranking] 처리 실패: orderId={}, partition={}, offset={}",
                orderId, partition, offset, e);
            // 커밋하지 않음 → 재처리
        }
    }

    private boolean isAlreadyProcessed(String eventId) {
        // Redis 또는 DB에서 중복 체크
        // return redisTemplate.hasKey("processed:" + eventId);
        return false;
    }
}
```

#### **Consumer 2: Data Platform Forwarder**

```java
@Component
@RequiredArgsConstructor
@Slf4j
class DataPlatformKafkaConsumer {
    private final DataPlatformClient dataPlatformClient;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "ecommerce.order.events",
        groupId = "data-platform",
        concurrency = "1"  // 순차 처리
    )
    public void consume(
        @Payload String payload,
        @Header(KafkaHeaders.RECEIVED_KEY) Long orderId,
        Acknowledgment ack
    ) {
        log.info("[DataPlatform] 메시지 수신: orderId={}", orderId);

        try {
            OrderCompletedEvent event =
                objectMapper.readValue(payload, OrderCompletedEvent.class);

            // 외부 데이터 플랫폼으로 전송 (HTTP, S3 등)
            dataPlatformClient.send(event);

            ack.acknowledge();

            log.info("[DataPlatform] 전송 완료: orderId={}", orderId);

        } catch (Exception e) {
            log.error("[DataPlatform] 전송 실패: orderId={}", orderId, e);
            // DLQ (Dead Letter Queue)로 이동 또는 알람
        }
    }
}
```

#### **Consumer 3: Analytics**

```java
@Component
@RequiredArgsConstructor
@Slf4j
class AnalyticsKafkaConsumer {
    private final InventoryAnalyticsService analyticsService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "ecommerce.order.events",
        groupId = "analytics",
        concurrency = "2"
    )
    public void consume(
        @Payload String payload,
        @Header(KafkaHeaders.RECEIVED_KEY) Long orderId,
        Acknowledgment ack
    ) {
        log.info("[Analytics] 메시지 수신: orderId={}", orderId);

        try {
            OrderCompletedEvent event =
                objectMapper.readValue(payload, OrderCompletedEvent.class);

            // 재고 분석
            analyticsService.analyzeInventory(event);

            // 판매 트렌드 분석
            analyticsService.analyzeSalesTrend(event);

            ack.acknowledge();

            log.info("[Analytics] 분석 완료: orderId={}", orderId);

        } catch (Exception e) {
            log.error("[Analytics] 분석 실패: orderId={}", orderId, e);
        }
    }
}
```

### 5.3 Consumer 설정

```yaml
# application.yml

spring:
  kafka:
    bootstrap-servers: localhost:9092,localhost:9093,localhost:9094
    consumer:
      group-id: ${CONSUMER_GROUP_ID:default-group}
      key-deserializer: org.apache.kafka.common.serialization.LongDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      enable-auto-commit: false          # 수동 커밋
      auto-offset-reset: earliest        # 처음부터 읽기
      max-poll-records: 500              # 한 번에 가져올 레코드 수
      fetch-min-bytes: 1                 # 최소 fetch 크기
      fetch-max-wait-ms: 500             # 최대 대기 시간
    listener:
      ack-mode: manual                   # 수동 Ack
      concurrency: 3                     # 기본 동시성

# Consumer별 설정
consumers:
  ranking-updater:
    concurrency: 3
    max-poll-records: 1000
  data-platform:
    concurrency: 1
    max-poll-records: 100
  analytics:
    concurrency: 2
    max-poll-records: 500
```

---

## 6. 마이그레이션 전략

### 6.1 단계별 마이그레이션

#### **Phase 1: Kafka 인프라 구축 (Week 1)**

```bash
# 1. Kafka 클러스터 실행
docker-compose -f docker-compose.kafka.yaml up -d

# 2. 클러스터 상태 확인
docker exec -it broker1 kafka-broker-api-versions \
  --bootstrap-server localhost:9092

# 3. Topic 생성
docker exec -it broker1 kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic ecommerce.order.events \
  --partitions 3 \
  --replication-factor 2 \
  --config retention.ms=604800000 \
  --config min.insync.replicas=2

# 4. Topic 확인
docker exec -it broker1 kafka-topics --list \
  --bootstrap-server localhost:9092

docker exec -it broker1 kafka-topics --describe \
  --bootstrap-server localhost:9092 \
  --topic ecommerce.order.events
```

**검증:**
```bash
# Producer 테스트 (콘솔)
docker exec -it broker1 kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic ecommerce.order.events \
  --property "parse.key=true" \
  --property "key.separator=:"

# 입력 예시:
12345:{"orderId":12345,"userId":67890,"total":45000}

# Consumer 테스트 (콘솔)
docker exec -it broker1 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic ecommerce.order.events \
  --from-beginning \
  --property print.key=true \
  --property key.separator=:
```

#### **Phase 2: Producer 구현 (Week 2)**

```
코드 구현:
  1. KafkaOrderEventHandler 추가
  2. OutboxKafkaProducer 추가
  3. OutboxKafkaDispatcher 추가
  4. 단위 테스트 작성

테스트:
  1. 로컬에서 주문 생성
  2. Outbox 테이블에 이벤트 저장 확인
  3. Kafka로 발행 확인 (kafka-console-consumer)
  4. 메시지 포맷 검증

주의:
  - 기존 Handler는 유지 (Dual Write)
  - Kafka로 발행만 추가
  - 실제 Consumer는 아직 활성화 안 함
```

#### **Phase 3: Consumer 구현 & 검증 (Week 3~4)**

```
코드 구현:
  1. RankingKafkaConsumer 추가
  2. DataPlatformKafkaConsumer 추가
  3. AnalyticsKafkaConsumer 추가
  4. 통합 테스트 작성

검증:
  1. 주문 생성 → Kafka 발행 → Consumer 수신 확인
  2. 처리 결과 정확성 검증
     - Ranking: Redis 데이터 확인
     - DataPlatform: 외부 전송 확인
     - Analytics: 분석 결과 확인
  3. 기존 Handler와 결과 비교 (일치 여부)
  4. 성능 테스트 (처리량, 지연시간)

주의:
  - Consumer는 enabled=false로 시작
  - 충분한 검증 후 활성화
```

#### **Phase 4: 트래픽 전환 (Week 5)**

```
트래픽 전환 계획:

1. Kafka Consumer 활성화
   consumers:
     ranking-updater:
       enabled: true
     data-platform:
       enabled: true
     analytics:
       enabled: true

2. 모니터링 강화
   - Consumer Lag 모니터링
   - 처리 처리량 모니터링
   - 에러율 모니터링

3. 점진적 전환
   Day 1: 10% 트래픽 (Kafka Consumer + 기존 Handler 병행)
   Day 2: 50% 트래픽
   Day 3: 100% 트래픽

4. 기존 Handler 비활성화
   @ConditionalOnProperty(name = "legacy.event.enabled", havingValue = "false")

5. 1주일 모니터링
   - 안정성 확인
   - 성능 이슈 없는지 확인
```

#### **Phase 5: 기존 시스템 제거 (Week 6)**

```
정리 작업:

1. 기존 Handler 코드 삭제
   - OrderDataPlatformEventHandler (Outbox → HTTP)
   - 관련 설정 파일

2. 기존 Outbox Dispatcher 로직 정리
   - HTTP 발송 로직 제거
   - Kafka 발송만 유지

3. 문서 업데이트
   - 아키텍처 문서
   - 운영 가이드
   - 트러블슈팅 가이드
```

### 6.2 롤백 계획

```
Phase 3~4에서 문제 발생 시:

1. 즉시 조치
   - Kafka Consumer 비활성화
     consumers.*.enabled: false

   - 기존 Handler 재활성화
     legacy.event.enabled: true

2. Kafka Offset 조정
   - 최신으로 이동 (메시지 스킵)
     kafka-consumer-groups --reset-offsets --to-latest

3. 원인 분석
   - 로그 수집
   - 메트릭 분석
   - 재현 테스트

4. 재시도
   - 문제 수정
   - 테스트 환경에서 재검증
   - 프로덕션 재배포
```

---

## 7. 확장 시나리오

### 7.1 새로운 Consumer 추가 (배포 없이)

```
시나리오:
  마케팅 팀에서 주문 완료 시 즉시 이메일 발송 요청

기존 방식:
  1. Order Service에 EmailHandler 추가
  2. 코드 변경 & 빌드
  3. Order Service 재배포
  4. 주문 서비스 다운타임 발생

Kafka 방식:
  1. 새로운 Marketing Service 개발 (독립 서비스)
  2. MarketingConsumer 구현
     @KafkaListener(
       topics = "ecommerce.order.events",
       groupId = "marketing"
     )
  3. Marketing Service만 배포
  4. Order Service 변경 없음 ✅
  5. 다운타임 없음 ✅
  6. Offset을 earliest로 설정 시 과거 데이터도 처리 가능 ✅

장점:
  - Order Service 무중단
  - 독립적인 개발/배포 주기
  - 실패 시 Marketing Service만 롤백
```

### 7.2 다른 도메인 이벤트 추가

```
확장 계획:

Topic 2: ecommerce.coupon.events
  - 쿠폰 발급 이벤트
  - 쿠폰 사용 이벤트
  - 쿠폰 만료 이벤트

Topic 3: ecommerce.user.events
  - 회원 가입 이벤트
  - 회원 탈퇴 이벤트
  - 프로필 변경 이벤트

Topic 4: ecommerce.product.events
  - 상품 등록 이벤트
  - 재고 변경 이벤트
  - 가격 변경 이벤트

통합 아키텍처:
┌─────────────────────────────────────────┐
│            Kafka Cluster                │
│                                         │
│  ├─ ecommerce.order.events              │
│  ├─ ecommerce.coupon.events             │
│  ├─ ecommerce.user.events               │
│  └─ ecommerce.product.events            │
└─────────────────────────────────────────┘
         │         │         │         │
         └─────────┴─────────┴─────────┘
                    │
       ┌────────────┼────────────┐
       │            │            │
  ┌────▼───┐  ┌────▼───┐  ┌────▼───┐
  │Analytics│  │Billing │  │Notify  │
  │Service  │  │Service │  │Service │
  └─────────┘  └────────┘  └────────┘

각 서비스가 필요한 Topic만 구독
```

### 7.3 마이크로서비스 확장

```
Monolithic → Microservices:

현재 (Monolithic):
┌────────────────────────────────┐
│      E-commerce Service        │
│                                │
│  - Order                       │
│  - User                        │
│  - Product                     │
│  - Payment                     │
└────────────────────────────────┘

목표 (Microservices + Kafka):
┌──────────┐   ┌──────────┐   ┌──────────┐
│  Order   │   │  User    │   │  Product │
│ Service  │   │ Service  │   │ Service  │
└─────┬────┘   └─────┬────┘   └─────┬────┘
      │              │              │
      └──────────────┼──────────────┘
                     │
              ┌──────▼──────┐
              │    Kafka    │
              └──────┬──────┘
                     │
      ┌──────────────┼──────────────┐
      │              │              │
┌─────▼────┐   ┌────▼─────┐   ┌────▼─────┐
│ Payment  │   │ Shipping │   │  Notify  │
│ Service  │   │ Service  │   │ Service  │
└──────────┘   └──────────┘   └──────────┘

마이그레이션 단계:
  1. Order Service 분리 (우선)
  2. User Service 분리
  3. Product Service 분리
  4. Payment Service 분리
  5. Shipping Service 분리

각 단계마다 Kafka를 통해 통신
```

---

## 8. 모니터링 및 운영

### 8.1 핵심 지표

#### **Producer 지표:**
```
지표:
  - 발행 성공률 (%)
  - 발행 지연시간 (ms)
  - Outbox PENDING 이벤트 수
  - Outbox FAILED 이벤트 수

알람:
  Critical:
    - 발행 실패율 > 5%
    - Outbox PENDING > 1,000개
    - Outbox FAILED > 100개

  Warning:
    - 발행 지연시간 > 5초
    - Outbox PENDING > 500개
```

#### **Consumer 지표:**
```
지표:
  - Consumer Lag (지연된 메시지 수)
  - 처리 처리량 (msg/sec)
  - 처리 실패율 (%)
  - Rebalance 횟수

알람:
  Critical:
    - Consumer Lag > 10,000
    - 처리 실패율 > 10%
    - Consumer Down

  Warning:
    - Consumer Lag > 1,000
    - 처리 지연시간 > 5초
    - Rebalance 빈도 > 10회/시간
```

#### **Kafka Cluster 지표:**
```
지표:
  - Broker 상태 (Up/Down)
  - Under-replicated Partitions
  - Disk 사용률 (%)
  - Network I/O (MB/s)

알람:
  Critical:
    - Broker Down
    - Under-replicated Partitions > 0
    - Disk 사용률 > 90%

  Warning:
    - Disk 사용률 > 80%
    - Network I/O > 80% capacity
```

### 8.2 운영 가이드

#### **Consumer Lag 대응:**
```
원인 분석:
  1. Consumer 처리 속도 < Producer 발행 속도
  2. Consumer 장애/재시작
  3. 네트워크 지연

대응:
  1. Consumer 수 증가 (Concurrency ↑)
     spring.kafka.listener.concurrency: 6

  2. 파티션 수 증가
     kafka-topics --alter \
       --topic ecommerce.order.events \
       --partitions 6

  3. Consumer 성능 튜닝
     - Batch 크기 증가 (max-poll-records)
     - Fetch 크기 증가 (fetch-min-bytes)

  4. 긴급 시: Offset 스킵 (데이터 유실)
     kafka-consumer-groups --reset-offsets --to-latest
```

#### **Outbox 장애 대응:**
```
증상:
  - Outbox PENDING 이벤트 급증
  - Kafka 발행 실패

원인:
  1. Kafka 클러스터 장애
  2. 네트워크 단절
  3. 메시지 크기 초과

대응:
  1. Kafka 클러스터 상태 확인
  2. Dispatcher 재시작
  3. Failed 이벤트 수동 재처리
     UPDATE outbox_events
     SET status = 'PENDING', retry_count = 0
     WHERE status = 'FAILED'
```

### 8.3 장애 시나리오

#### **시나리오 1: Kafka 클러스터 장애**
```
상황:
  - 모든 Broker Down
  - Producer 발행 실패

영향:
  ✅ 주문 처리는 정상 (Outbox에 저장됨)
  ❌ Consumer가 메시지 수신 못 함

대응:
  1. Kafka 클러스터 복구
  2. Outbox Dispatcher가 자동으로 재발행
  3. Consumer가 메시지 처리 재개

데이터 손실: 없음
```

#### **시나리오 2: Consumer 장애**
```
상황:
  - Ranking Consumer Down

영향:
  ✅ 주문 처리는 정상
  ✅ Kafka에는 메시지 저장됨
  ❌ 랭킹 업데이트 지연

대응:
  1. Consumer 재시작
  2. Offset부터 자동 재처리
  3. 지연된 랭킹 업데이트

데이터 손실: 없음
```

#### **시나리오 3: 파티션 리더 장애**
```
상황:
  - Partition 0의 Leader Broker Down

자동 복구:
  1. Zookeeper가 감지
  2. Follower를 새로운 Leader로 선출
  3. Producer/Consumer가 새 Leader로 연결

다운타임: 수 초
데이터 손실: 없음 (Replication)
```

---

## 9. 체크리스트

### 9.1 구현 전 확인사항

- [ ] Kafka 기초 개념 학습 완료
- [ ] 현재 시스템 아키텍처 이해
- [ ] Topic 설계 완료 (파티션, Replication, Retention)
- [ ] 메시지 스키마 설계 완료
- [ ] Producer 설계 검토
- [ ] Consumer 설계 검토
- [ ] 마이그레이션 계획 수립
- [ ] 롤백 계획 수립
- [ ] 모니터링 계획 수립

### 9.2 구현 후 확인사항

- [ ] Kafka 클러스터 정상 동작
- [ ] Topic 생성 확인
- [ ] Producer 발행 테스트 통과
- [ ] Consumer 수신 테스트 통과
- [ ] Outbox 패턴 동작 확인
- [ ] 멱등성 테스트 통과
- [ ] 순서 보장 테스트 통과
- [ ] 성능 테스트 통과
- [ ] 장애 시나리오 테스트 통과
- [ ] 모니터링 대시보드 구축

### 9.3 프로덕션 배포 전 확인사항

- [ ] 충분한 통합 테스트 완료
- [ ] 부하 테스트 완료 (목표 처리량 달성)
- [ ] 장애 복구 테스트 완료
- [ ] 롤백 절차 검증 완료
- [ ] 모니터링 알람 설정 완료
- [ ] 운영 가이드 문서화 완료
- [ ] 팀 교육 완료
- [ ] 프로덕션 환경 설정 완료
- [ ] 백업 계획 수립 완료

---

## 10. 다음 단계

### 10.1 학습 단계

1. **Kafka 기초 개념 학습** (완료)
   - docs/claude-code/kafka-fundamentals.md 참고

2. **로컬 환경 구축**
   - docker-compose.kafka.yaml 실행
   - 콘솔 Producer/Consumer 테스트

3. **Spring Kafka 통합**
   - KafkaTemplate 학습
   - @KafkaListener 학습
   - 샘플 프로젝트 구현

### 10.2 구현 단계

1. **Producer 구현** (Week 2)
   - KafkaOrderEventHandler
   - OutboxKafkaProducer
   - OutboxKafkaDispatcher
   - 단위 테스트

2. **Consumer 구현** (Week 3~4)
   - RankingKafkaConsumer
   - DataPlatformKafkaConsumer
   - AnalyticsKafkaConsumer
   - 통합 테스트

3. **마이그레이션** (Week 5~6)
   - 점진적 트래픽 전환
   - 모니터링
   - 기존 시스템 제거

### 10.3 확장 단계

1. **다른 도메인 이벤트 추가**
   - ecommerce.coupon.events
   - ecommerce.user.events
   - ecommerce.product.events

2. **마이크로서비스 전환**
   - 서비스 분리
   - Kafka 기반 통신

3. **고급 기능**
   - Kafka Streams (실시간 집계)
   - Schema Registry (스키마 관리)
   - Kafka Connect (DB 동기화)

---

## 결론

이 설계를 바탕으로 Kafka를 통합하면:

✅ **시스템 간 결합도 감소**
- Producer와 Consumer 독립적 개발/배포

✅ **확장성 확보**
- 파티션 추가로 처리량 증대
- Consumer 추가로 병렬 처리

✅ **데이터 재처리 가능**
- Offset 리셋으로 과거 데이터 재처리
- 새로운 분석 시스템 추가 용이

✅ **마이크로서비스 준비**
- 이벤트 기반 아키텍처
- 서비스 간 느슨한 결합

**단계적으로 접근하여 안전하게 전환합시다!** 🚀

질문이나 추가 논의가 필요하면 언제든 말씀해주세요! 😊
