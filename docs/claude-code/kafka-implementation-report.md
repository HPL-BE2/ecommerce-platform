# Kafka 통합 구현 보고서

> 실시간 주문정보를 Kafka 메시지로 발행하도록 변경한 구현 보고서

## 📚 목차
1. [구현 개요](#1-구현-개요)
2. [아키텍처](#2-아키텍처)
3. [구현 내용](#3-구현-내용)
4. [실행 방법](#4-실행-방법)
5. [테스트 방법](#5-테스트-방법)
6. [모니터링](#6-모니터링)
7. [트러블슈팅](#7-트러블슈팅)

---

## 1. 구현 개요

### 1.1 목표

**기존 Spring Application Event 기반 아키텍처를 Kafka 기반으로 전환**

- 시스템 간 결합도 감소
- 확장성 확보 (수평 확장)
- 데이터 재처리 가능
- 마이크로서비스 아키텍처 준비

### 1.2 구현 범위

#### ✅ 완료된 작업

1. **의존성 추가**
   - `spring-kafka` 추가

2. **Kafka 설정**
   - Producer/Consumer 설정 (application.yml)
   - Topic 자동 생성 설정

3. **Producer 구현**
   - `KafkaOrderEventHandler`: Domain Event → Outbox Event
   - `OutboxKafkaProducer`: Outbox 테이블 저장
   - `OutboxKafkaDispatcher`: Outbox → Kafka 발행

4. **Consumer 구현**
   - `RankingKafkaConsumer`: Redis 랭킹 업데이트
   - `DataPlatformKafkaConsumer`: 외부 데이터 플랫폼 전송
   - `AnalyticsKafkaConsumer`: 재고 분석

5. **문서 작성**
   - Kafka 기초 개념 학습 가이드
   - Kafka 통합 아키텍처 설계
   - Kafka 로컬 설치 및 테스트 가이드

---

## 2. 아키텍처

### 2.1 전체 흐름

```
┌─────────────────────────────────────────────────────────────────┐
│                       Order Service                              │
│                                                                  │
│  [주문 완료] → OrderCompletedDomainEvent                        │
│       │                                                          │
│       ├──→ OrderRankingEventHandler (기존: Redis 직접 업데이트) │
│       ├──→ OrderDataPlatformEventHandler (기존: HTTP 전송)      │
│       └──→ KafkaOrderEventHandler (신규: Kafka 발행) ← 추가!   │
│              │                                                   │
│              └──→ OutboxKafkaProducer                           │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
              ┌────────────────────────┐
              │   Outbox Table         │
              │  (PENDING 상태 저장)   │
              └────────┬───────────────┘
                       │
                 ┌─────▼──────┐
                 │ Outbox     │
                 │ Dispatcher │ (Scheduled 5초)
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
│  │  - Key: orderId                                            │ │
│  └────────────────────────────────────────────────────────────┘ │
└───────────┬───────────────┬──────────────┬─────────────────────┘
            │               │              │
    ┌───────▼────┐  ┌──────▼─────┐  ┌────▼─────────┐
    │ Ranking    │  │ Analytics  │  │ Data         │
    │ Consumer   │  │ Consumer   │  │ Platform     │
    │ (Group 1)  │  │ (Group 2)  │  │ Consumer     │
    │ 동시성: 3  │  │ 동시성: 2  │  │ (Group 3)    │
    │            │  │            │  │ 동시성: 1    │
    └────┬───────┘  └──────┬─────┘  └──────┬───────┘
         │                 │               │
         ▼                 ▼               ▼
    [Redis Cache]   [Analytics DB]  [Data Lake]
```

### 2.2 핵심 설계 원칙

#### **1) Transactional Outbox Pattern**

```java
// Order 저장과 Outbox Event 저장이 같은 트랜잭션
@Transactional
public void completeOrder(Order order) {
    orderRepo.save(order);  // 1. Order 저장
    eventPublisher.publishEvent(event);  // 2. Domain Event 발행
    // → KafkaOrderEventHandler가 Outbox에 저장 (별도 트랜잭션)
}

// Outbox Dispatcher (별도 프로세스)
@Scheduled(fixedDelay = 5000)
public void dispatch() {
    // 1. Outbox PENDING 조회
    // 2. Kafka 발행
    // 3. 성공 시 SENT 상태 변경
}
```

**장점:**
- ✅ 데이터 일관성 보장 (Order 성공 = 이벤트 발행 보장)
- ✅ Kafka 장애 시에도 안전 (Outbox에 보관)
- ✅ At-Least-Once 전달 보장

#### **2) Key 기반 파티셔닝**

```java
// 같은 orderId는 같은 파티션으로
kafkaTemplate.send(
    "ecommerce.order.events",
    event.orderId(),  // Key
    payload           // Value
);

// 파티션 결정
hash(orderId) % 3 = partition_number
```

**장점:**
- ✅ 같은 주문 ID는 순서 보장
- ✅ 파티션별 병렬 처리

#### **3) Consumer Group 분리**

```yaml
Consumer Group: ranking-updater
  - 동시성: 3
  - 목적: Redis 랭킹 업데이트

Consumer Group: data-platform
  - 동시성: 1
  - 목적: 외부 데이터 플랫폼 전송

Consumer Group: analytics
  - 동시성: 2
  - 목적: 재고 분석
```

**장점:**
- ✅ 독립적인 Offset 관리
- ✅ 서로 영향 없음

---

## 3. 구현 내용

### 3.1 의존성 추가

**파일:** `build.gradle.kts`

```kotlin
dependencies {
    // Kafka
    implementation("org.springframework.kafka:spring-kafka")
}
```

### 3.2 Kafka 설정

**파일:** `src/main/resources/application.yml`

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092,localhost:9093,localhost:9094
    producer:
      key-serializer: org.apache.kafka.common.serialization.LongSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all                      # 모든 복제본 확인
      retries: 3                     # 재시도
      properties:
        enable.idempotence: true     # 중복 방지
        compression.type: snappy     # 압축
    consumer:
      group-id: ${CONSUMER_GROUP_ID:default-group}
      key-deserializer: org.apache.kafka.common.serialization.LongDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      enable-auto-commit: false      # 수동 커밋
      auto-offset-reset: earliest    # 처음부터 읽기
    listener:
      ack-mode: manual               # 수동 Ack
      concurrency: 3                 # 기본 동시성
```

### 3.3 Producer 구현

#### **KafkaOrderEventHandler**

**위치:** `src/main/java/kr/hhplus/be/server/application/event/KafkaOrderEventHandler.java`

**역할:**
- Domain Event를 Outbox Event로 변환
- Outbox 테이블에 저장

**핵심 로직:**
```java
@Async
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handle(OrderCompletedDomainEvent domainEvent) {
    OrderCompletedEvent outboxEvent = convert(domainEvent);
    outboxProducer.publish(outboxEvent);
}
```

#### **OutboxKafkaProducer**

**위치:** `src/main/java/kr/hhplus/be/server/infrastructure/outbox/OutboxKafkaProducer.java`

**역할:**
- Outbox 테이블에 PENDING 상태로 저장

**핵심 로직:**
```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void publish(OrderCompletedEvent event) {
    OutboxEventEntity entity = new OutboxEventEntity();
    entity.setEventType("ORDER_COMPLETED");
    entity.setPayload(toJson(event));
    entity.setStatus(PENDING);
    outboxRepository.save(entity);
}
```

#### **OutboxKafkaDispatcher**

**위치:** `src/main/java/kr/hhplus/be/server/infrastructure/outbox/OutboxKafkaDispatcher.java`

**역할:**
- 5초마다 Outbox PENDING 조회
- Kafka로 발행
- 성공 시 SENT, 실패 시 재시도

**핵심 로직:**
```java
@Scheduled(fixedDelay = 5000)
@Transactional
public void dispatch() {
    List<OutboxEventEntity> events = findPending();

    for (OutboxEventEntity event : events) {
        try {
            kafkaTemplate.send(TOPIC, orderId, payload).get(10, SECONDS);
            event.setStatus(SENT);
        } catch (Exception ex) {
            handleFailure(event, ex);  // Exponential Backoff
        }
    }
}
```

### 3.4 Consumer 구현

#### **RankingKafkaConsumer**

**위치:** `src/main/java/kr/hhplus/be/server/infrastructure/kafka/RankingKafkaConsumer.java`

**설정:**
- Group ID: `ranking-updater`
- Concurrency: `3`
- 목적: Redis 랭킹 업데이트

**핵심 로직:**
```java
@KafkaListener(
    topics = "ecommerce.order.events",
    groupId = "ranking-updater",
    concurrency = "3"
)
public void consume(String payload, Long orderId, Acknowledgment ack) {
    OrderCompletedEvent event = parse(payload);
    rankingUpdater.handle(event);
    ack.acknowledge();  // 수동 커밋
}
```

#### **DataPlatformKafkaConsumer**

**위치:** `src/main/java/kr/hhplus/be/server/infrastructure/kafka/DataPlatformKafkaConsumer.java`

**설정:**
- Group ID: `data-platform`
- Concurrency: `1` (순차 처리)
- 목적: 외부 데이터 플랫폼 전송

**핵심 로직:**
```java
@KafkaListener(
    topics = "ecommerce.order.events",
    groupId = "data-platform",
    concurrency = "1"
)
public void consume(String payload, Long orderId, Acknowledgment ack) {
    OrderCompletedEvent event = parse(payload);
    // dataPlatformClient.send(event);  // 향후 구현
    ack.acknowledge();
}
```

#### **AnalyticsKafkaConsumer**

**위치:** `src/main/java/kr/hhplus/be/server/infrastructure/kafka/AnalyticsKafkaConsumer.java`

**설정:**
- Group ID: `analytics`
- Concurrency: `2`
- 목적: 재고 분석 및 트렌드 분석

**핵심 로직:**
```java
@KafkaListener(
    topics = "ecommerce.order.events",
    groupId = "analytics",
    concurrency = "2"
)
public void consume(String payload, Long orderId, Acknowledgment ack) {
    OrderCompletedEvent event = parse(payload);
    // analyticsService.analyze(event);  // 향후 구현
    ack.acknowledge();
}
```

### 3.5 Kafka Configuration

**위치:** `src/main/java/kr/hhplus/be/server/infrastructure/config/KafkaConfig.java`

**역할:**
- Topic 자동 생성 설정

**핵심 로직:**
```java
@Bean
public NewTopic orderEventsTopic() {
    return TopicBuilder.name("ecommerce.order.events")
            .partitions(3)
            .replicas(2)
            .config("retention.ms", "604800000")  // 7일
            .config("min.insync.replicas", "2")
            .build();
}
```

---

## 4. 실행 방법

### 4.1 Kafka 클러스터 실행

```bash
# Kafka 클러스터 시작
docker compose -f docker-compose.kafka.yaml up -d

# 상태 확인
docker ps

# 로그 확인
docker compose -f docker-compose.kafka.yaml logs -f broker1
```

### 4.2 애플리케이션 실행

```bash
# 빌드
./gradlew clean build

# 실행
./gradlew bootRun

# 또는
java -jar build/libs/hhplus-0.0.1-SNAPSHOT.jar
```

### 4.3 Topic 확인

```bash
# Topic 목록
docker exec -it broker1 kafka-topics --list \
  --bootstrap-server localhost:9092

# Topic 상세 정보
docker exec -it broker1 kafka-topics --describe \
  --bootstrap-server localhost:9092 \
  --topic ecommerce.order.events

# 예상 출력:
# Topic: ecommerce.order.events
# Partition: 0  Leader: 1  Replicas: 1,2  Isr: 1,2
# Partition: 1  Leader: 2  Replicas: 2,3  Isr: 2,3
# Partition: 2  Leader: 3  Replicas: 3,1  Isr: 3,1
```

---

## 5. 테스트 방법

### 5.1 주문 생성 테스트

```bash
# 주문 생성 API 호출
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "items": [
      {"productId": 101, "quantity": 2}
    ]
  }'

# 예상 응답:
# {
#   "orderId": 12345,
#   "total": 50000,
#   "status": "COMPLETED"
# }
```

### 5.2 Outbox 테이블 확인

```sql
-- Outbox 이벤트 확인
SELECT id, event_type, aggregate_id, status, created_at
FROM outbox_events
ORDER BY id DESC
LIMIT 10;

-- 예상 결과:
-- id  | event_type      | aggregate_id | status | created_at
-- 1   | ORDER_COMPLETED | 12345        | SENT   | 2025-12-01 10:30:00
```

### 5.3 Kafka 메시지 확인

```bash
# Consumer로 메시지 확인
docker exec -it broker1 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic ecommerce.order.events \
  --from-beginning \
  --property print.key=true \
  --property print.partition=true \
  --property print.offset=true

# 예상 출력:
# Partition:0  Offset:0  Key:12345  Value:{"orderId":12345,"userId":1,...}
```

### 5.4 Consumer Group 확인

```bash
# Consumer Group 상태 확인
docker exec -it broker1 kafka-consumer-groups --describe \
  --bootstrap-server localhost:9092 \
  --group ranking-updater

# 예상 출력:
# GROUP           TOPIC                    PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
# ranking-updater ecommerce.order.events   0          5               5               0
# ranking-updater ecommerce.order.events   1          5               5               0
# ranking-updater ecommerce.order.events   2          5               5               0
```

### 5.5 애플리케이션 로그 확인

```bash
# Kafka 관련 로그 필터링
tail -f logs/application.log | grep -E "\[Kafka\]|\[Ranking\]|\[DataPlatform\]|\[Analytics\]"

# 예상 로그:
# [Kafka] 주문 완료 이벤트 수신 orderId=12345
# [Kafka] Outbox 저장 완료 orderId=12345
# [Kafka] Outbox 이벤트 발행 시작: count=1
# [Kafka] 발행 성공: id=1, orderId=12345
# [Ranking] 메시지 수신: orderId=12345, partition=0, offset=0
# [Ranking] 랭킹 업데이트 완료: orderId=12345, items=2
# [DataPlatform] 메시지 수신: orderId=12345
# [DataPlatform] 전송 완료: orderId=12345
# [Analytics] 메시지 수신: orderId=12345
# [Analytics] 분석 완료: orderId=12345
```

---

## 6. 모니터링

### 6.1 핵심 지표

#### **Producer 지표**
- Outbox PENDING 이벤트 수
- Outbox FAILED 이벤트 수
- Kafka 발행 성공률
- Kafka 발행 지연시간

```sql
-- Outbox 상태별 집계
SELECT status, COUNT(*)
FROM outbox_events
WHERE created_at >= NOW() - INTERVAL 1 HOUR
GROUP BY status;
```

#### **Consumer 지표**
- Consumer Lag
- 처리 처리량 (msg/sec)
- 처리 실패율

```bash
# Consumer Lag 모니터링
docker exec -it broker1 kafka-consumer-groups --describe \
  --bootstrap-server localhost:9092 \
  --group ranking-updater

# LAG 컬럼 확인
```

#### **Kafka Cluster 지표**
- Broker 상태
- Under-replicated Partitions
- Disk 사용률

```bash
# Broker 상태
docker ps --filter "name=broker"

# Under-replicated Partitions 확인
docker exec -it broker1 kafka-topics --describe \
  --bootstrap-server localhost:9092 \
  --under-replicated-partitions
```

### 6.2 알람 설정 (권장)

```yaml
alerts:
  critical:
    - outbox_pending > 1000
    - outbox_failed > 100
    - consumer_lag > 10000
    - broker_down

  warning:
    - outbox_pending > 500
    - consumer_lag > 1000
    - publish_latency > 5s
```

---

## 7. 트러블슈팅

### 7.1 Outbox 이벤트가 PENDING에서 멈춤

**증상:**
```sql
SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING';
-- 1000+
```

**원인:**
1. Kafka 클러스터 장애
2. Dispatcher 비활성화
3. 네트워크 문제

**해결:**
```bash
# 1. Kafka 상태 확인
docker ps --filter "name=broker"

# 2. Dispatcher 설정 확인
# application.yml
outbox:
  dispatcher:
    enabled: true  # ← 확인

# 3. Kafka 재시작
docker restart broker1 broker2 broker3

# 4. 애플리케이션 재시작
./gradlew bootRun
```

### 7.2 Consumer Lag 발생

**증상:**
```
LAG > 1000
```

**원인:**
- Consumer 처리 속도 < Producer 발행 속도

**해결:**
```yaml
# Concurrency 증가
spring:
  kafka:
    listener:
      concurrency: 6  # 3 → 6

# 또는 파티션 증가
docker exec -it broker1 kafka-topics --alter \
  --bootstrap-server localhost:9092 \
  --topic ecommerce.order.events \
  --partitions 6
```

### 7.3 메시지 중복 처리

**증상:**
- 같은 orderId가 여러 번 처리됨

**원인:**
- Consumer 재시작 전 Offset 미커밋

**해결:**
```java
// 멱등성 체크 추가
@KafkaListener(...)
public void consume(String payload, Acknowledgment ack) {
    OrderCompletedEvent event = parse(payload);

    // 중복 체크
    if (isAlreadyProcessed(event.orderId())) {
        ack.acknowledge();
        return;
    }

    process(event);
    markAsProcessed(event.orderId());
    ack.acknowledge();
}
```

---

## 8. 다음 단계

### 8.1 단기 (1~2주)
- [ ] 통합 테스트 작성
- [ ] 성능 테스트 (처리량 측정)
- [ ] 모니터링 대시보드 구축 (Grafana)

### 8.2 중기 (1~2개월)
- [ ] DataPlatform Consumer 실제 구현
- [ ] Analytics Consumer 실제 구현
- [ ] 기존 OrderDataPlatformEventHandler 제거
- [ ] 다른 도메인 이벤트 추가 (Coupon, User, Product)

### 8.3 장기 (3~6개월)
- [ ] 마이크로서비스 분리
- [ ] Schema Registry 도입
- [ ] Kafka Streams 활용 (실시간 집계)
- [ ] CDC (Change Data Capture) 도입

---

## 9. 결론

### 9.1 구현 성과

✅ **완료된 작업:**
- Spring Kafka 통합 완료
- Transactional Outbox Pattern 구현
- Producer/Consumer 구현 완료
- 3개 Consumer Group (Ranking, DataPlatform, Analytics)

✅ **달성한 목표:**
- 시스템 간 결합도 감소
- 확장 가능한 아키텍처
- 데이터 재처리 가능
- 마이크로서비스 준비 완료

### 9.2 기대 효과

**1) 확장성**
- 파티션 추가 → 처리량 증대
- Consumer 추가 → 병렬 처리

**2) 안정성**
- Outbox Pattern → 데이터 일관성
- Replication → 고가용성
- 재시도 메커니즘 → 장애 복구

**3) 유연성**
- 새로운 Consumer 추가 용이
- 코드 변경 없이 확장
- 과거 데이터 재처리 가능

**4) 마이크로서비스 전환**
- 느슨한 결합
- 독립적인 배포
- 이벤트 기반 통신

---

## 참고 문서

- [Kafka 기초 개념 학습 가이드](./kafka-fundamentals.md)
- [Kafka 통합 아키텍처 설계](./kafka-integration-design.md)
- [Kafka 로컬 설치 및 테스트 가이드](./kafka-local-setup-guide.md)

**구현 완료! 🚀**
