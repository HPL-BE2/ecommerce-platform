# Kafka 기초 개념 학습 가이드

> 이 문서는 Kafka를 처음 접하는 동료들을 위한 학습 자료입니다.

## 📚 목차
1. [Kafka란 무엇인가?](#1-kafka란-무엇인가)
2. [Kafka의 주요 특징](#2-kafka의-주요-특징)
3. [핵심 구성 요소](#3-핵심-구성-요소)
4. [Kafka 사용의 장단점](#4-kafka-사용의-장단점)
5. [핵심 기능](#5-핵심-기능)
6. [이벤트 기반 아키텍처 확장](#6-이벤트-기반-아키텍처-확장)

---

## 1. Kafka란 무엇인가?

### 1.1 정의
**Apache Kafka**는 **분산 이벤트 스트리밍 플랫폼**입니다.

간단히 말하면:
- 대용량의 데이터를 **실시간으로 수집**하고
- **안전하게 저장**하며
- 여러 시스템이 이를 **구독하여 처리**할 수 있게 하는 메시징 시스템입니다.

### 1.2 왜 만들어졌나?
LinkedIn에서 시작된 Kafka는 다음과 같은 문제를 해결하기 위해 만들어졌습니다:

```
문제 상황:
┌─────────┐     ┌─────────┐     ┌─────────┐
│ 주문    │────>│ 랭킹    │     │ 분석    │
│ 시스템  │     │ 시스템  │     │ 시스템  │
└─────────┘     └─────────┘     └─────────┘
     │                                │
     └────────────────────────────────┘
           (직접 연결 - 강한 결합)

문제점:
1. 주문 시스템이 모든 시스템을 알아야 함
2. 하나의 시스템 장애가 전체에 영향
3. 새로운 시스템 추가 시 기존 코드 수정 필요
4. 실시간 대용량 데이터 처리 어려움
```

```
Kafka 도입 후:
┌─────────┐
│ 주문    │
│ 시스템  │───┐
└─────────┘   │
              ▼
         ┌─────────┐
         │  Kafka  │
         └─────────┘
              │
    ┌─────────┼─────────┐
    ▼         ▼         ▼
┌────────┐┌────────┐┌────────┐
│ 랭킹   ││ 분석   ││ 알림   │
│ 시스템 ││ 시스템 ││ 시스템 │
└────────┘└────────┘└────────┘

장점:
1. 느슨한 결합 (Decoupling)
2. 독립적인 확장
3. 새로운 시스템 추가 용이
4. 대용량 실시간 처리
```

---

## 2. Kafka의 주요 특징

### 2.1 고성능 & 고가용성

#### ✅ 파티셔닝 (Partitioning)
```
Topic: order-events
┌──────────────────────────────────────┐
│ Partition 0: [msg1, msg4, msg7, ...] │
│ Partition 1: [msg2, msg5, msg8, ...] │
│ Partition 2: [msg3, msg6, msg9, ...] │
└──────────────────────────────────────┘
```

**장점:**
- 여러 파티션에 데이터를 분산 저장
- 파티션별 병렬 처리 가능
- 수평 확장 (파티션 추가)

**예시:**
```
파티션 3개 → Consumer 3개까지 병렬 처리 가능
처리량: 1,000 msg/sec × 3 = 3,000 msg/sec
```

#### ✅ 복제 (Replication)
```
Broker 1: [Partition 0 (Leader), Partition 1 (Replica)]
Broker 2: [Partition 0 (Replica), Partition 2 (Leader)]
Broker 3: [Partition 1 (Leader), Partition 2 (Replica)]
```

**장점:**
- 데이터를 여러 브로커에 복제
- 브로커 장애 시 자동 복구
- 데이터 손실 방지

**예시:**
```
Replication Factor: 3
→ 2개 브로커 장애까지 견딜 수 있음
```

#### ✅ 수평 확장
```
초기: 브로커 3개 → 처리량 10,000 msg/sec
확장: 브로커 6개 → 처리량 20,000 msg/sec
```

---

### 2.2 내구성 (Durability)

```
┌─────────────────────────────────────┐
│         Kafka Broker                │
│                                     │
│  Memory Buffer (빠른 쓰기)          │
│         ↓                           │
│  Disk Storage (영구 저장)           │
│  - /var/kafka/data                  │
│  - 설정된 기간/크기만큼 보관         │
└─────────────────────────────────────┘
```

**특징:**
- 모든 메시지를 디스크에 영구 저장
- 브로커 재시작 후에도 데이터 유지
- Retention 정책에 따라 보관 (예: 7일)

**예시:**
```yaml
Retention Policy:
  - 시간 기반: 7일 (604800000 ms)
  - 크기 기반: 1GB
  - 무한 보관: Compaction 사용
```

---

### 2.3 분산 처리

```
Topic: order-events (Partition 3개)

Consumer Group: ranking-updater
┌─────────────────────────────────────┐
│ Consumer 1 → Partition 0 처리       │
│ Consumer 2 → Partition 1 처리       │
│ Consumer 3 → Partition 2 처리       │
└─────────────────────────────────────┘

처리량: 각 Consumer가 독립적으로 병렬 처리
순서 보장: 같은 파티션 내에서만 순서 보장
```

**Consumer Group의 장점:**
- 파티션별로 Consumer 자동 할당
- Consumer 추가/제거 시 자동 리밸런싱
- 각 Consumer Group은 독립적인 Offset 관리

---

## 3. 핵심 구성 요소

### 3.1 전체 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                    Kafka Cluster                            │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │                   Zookeeper                          │  │
│  │         (메타데이터 관리, 리더 선출)                 │  │
│  └──────────────────────────────────────────────────────┘  │
│                          ↓                                   │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                  │
│  │ Broker 1 │  │ Broker 2 │  │ Broker 3 │                  │
│  │ (9092)   │  │ (9093)   │  │ (9094)   │                  │
│  └──────────┘  └──────────┘  └──────────┘                  │
│                                                              │
│  ┌────────────────────────────────────────┐                 │
│  │  Topic: order-events                   │                 │
│  │  ┌──────────┐ ┌──────────┐ ┌────────┐ │                 │
│  │  │Partition0│ │Partition1│ │Partition2│ │                 │
│  │  │  Leader  │ │  Leader  │ │  Leader  │ │                 │
│  │  │ Replica  │ │ Replica  │ │ Replica  │ │                 │
│  │  └──────────┘ └──────────┘ └────────┘ │                 │
│  └────────────────────────────────────────┘                 │
└─────────────────────────────────────────────────────────────┘
         ↑                                    ↓
    [Producer]                           [Consumer Group]
   (메시지 발행)                         (메시지 소비)
```

---

### 3.2 Broker

**정의:** Kafka 서버 인스턴스

**역할:**
- 메시지를 받아서 디스크에 저장
- Consumer 요청에 따라 메시지 전달
- 파티션의 Leader/Replica 관리

**특징:**
```
Broker 1 (ID: 1)
  - 저장 경로: /var/kafka/data
  - 관리 파티션:
    - order-events-0 (Leader)
    - order-events-1 (Replica)
  - 포트: 9092
```

---

### 3.3 Topic

**정의:** 메시지의 논리적 채널 (카테고리)

**비유:**
```
Topic = 우편함
  - order-events → 주문 관련 메시지
  - user-events → 회원 관련 메시지
  - coupon-events → 쿠폰 관련 메시지
```

**생성 예시:**
```bash
kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic order-events \
  --partitions 3 \
  --replication-factor 2
```

---

### 3.4 Partition

**정의:** Topic의 물리적 분할 단위

**파티셔닝 전략:**
```
메시지에 Key가 있는 경우:
  Key: orderId=12345 → hash(12345) % 3 = Partition 1
  Key: orderId=12346 → hash(12346) % 3 = Partition 2

같은 orderId는 항상 같은 파티션으로!
→ 주문 단위 순서 보장

메시지에 Key가 없는 경우:
  Round-robin 방식으로 분산
```

**순서 보장:**
```
Partition 0: [A1, A2, A3, ...] ← 순서 보장 ✅
Partition 1: [B1, B2, B3, ...] ← 순서 보장 ✅
Partition 2: [C1, C2, C3, ...] ← 순서 보장 ✅

하지만 A1, B1, C1의 전체 순서는 보장 안 됨 ❌
```

---

### 3.5 Producer

**정의:** 메시지를 Topic에 발행하는 애플리케이션

**흐름:**
```java
// 1. KafkaProducer 생성
KafkaProducer<Long, OrderEvent> producer = new KafkaProducer<>(config);

// 2. 메시지 발행
ProducerRecord<Long, OrderEvent> record = new ProducerRecord<>(
    "order-events",        // Topic
    event.getOrderId(),    // Key (파티셔닝 기준)
    event                  // Value
);

producer.send(record);  // 비동기 전송

// 3. 동기 전송 (확인 필요 시)
producer.send(record).get();  // 전송 완료까지 대기
```

**중요 설정:**
```yaml
Producer Config:
  acks: all               # 모든 복제본 확인 (안전성 최대)
  retries: 3              # 실패 시 재시도
  enable.idempotence: true  # 중복 방지
  compression.type: snappy  # 압축
```

---

### 3.6 Consumer & Consumer Group

**정의:**
- **Consumer:** 메시지를 읽는 애플리케이션
- **Consumer Group:** 하나의 Topic을 여러 Consumer가 분산 처리

**Consumer Group 예시:**
```
Topic: order-events (Partition 3개)

Consumer Group: ranking-updater
  - Consumer 1 → Partition 0
  - Consumer 2 → Partition 1
  - Consumer 3 → Partition 2

Consumer Group: data-platform
  - Consumer 1 → Partition 0, 1, 2 (모든 파티션)

Consumer Group: analytics
  - Consumer 1 → Partition 0, 1
  - Consumer 2 → Partition 2
```

**핵심 특징:**
1. **독립적인 Offset 관리**
   ```
   ranking-updater: offset 1000
   data-platform: offset 500
   analytics: offset 1500

   → 각자 다른 위치에서 읽음
   ```

2. **자동 리밸런싱**
   ```
   초기: Consumer 2개 → Partition 3개
     - Consumer 1: Partition 0, 1
     - Consumer 2: Partition 2

   Consumer 3 추가 후:
     - Consumer 1: Partition 0
     - Consumer 2: Partition 1
     - Consumer 3: Partition 2
   ```

3. **파티션 < Consumer 수**
   ```
   Partition 3개, Consumer 5개인 경우:
     - Consumer 1, 2, 3: 각각 1개 파티션 처리
     - Consumer 4, 5: 유휴 상태 (아무것도 안 함)
   ```

---

### 3.7 Offset

**정의:** Consumer가 어디까지 읽었는지 나타내는 위치 정보

```
Partition 0:
[msg0][msg1][msg2][msg3][msg4][msg5][msg6]...
  ↑     ↑                       ↑
  0     1                       5 ← Current Offset

Consumer가 Offset 5까지 읽음
→ 다음에는 Offset 6부터 시작
```

**Offset 관리 방식:**
```java
// 1. 자동 커밋 (Auto Commit)
enable.auto.commit: true
auto.commit.interval.ms: 5000  // 5초마다 자동 커밋

장점: 편리함
단점: 메시지 처리 실패 시에도 커밋될 수 있음

// 2. 수동 커밋 (Manual Commit)
@KafkaListener(...)
public void consume(OrderEvent event, Acknowledgment ack) {
    try {
        process(event);
        ack.acknowledge();  // 처리 완료 후 커밋
    } catch (Exception ex) {
        // 커밋 안 함 → 재처리
    }
}

장점: 안전성 높음
단점: 코드 복잡도 증가
```

**Offset 리셋 (재처리):**
```bash
# 처음부터 다시 읽기
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group ranking-updater \
  --topic order-events \
  --reset-offsets --to-earliest \
  --execute

# 특정 시간부터 읽기
--reset-offsets --to-datetime 2025-12-01T00:00:00.000
```

---

### 3.8 Zookeeper (또는 KRaft)

**정의:** Kafka 클러스터의 메타데이터 관리 시스템

**역할:**
- 브로커 상태 관리
- 파티션 리더 선출
- 클러스터 구성 정보 저장
- Consumer Group 정보 관리

**주의:**
- Kafka 3.x 이후: KRaft 모드 지원 (Zookeeper 불필요)
- 현재 제공된 docker-compose는 Zookeeper 사용

---

## 4. Kafka 사용의 장단점

### 4.1 장점

#### ✅ 1. 시스템 간 결합도 감소 (Decoupling)

**Before Kafka:**
```java
@Service
class OrderService {
    private RankingService rankingService;
    private AnalyticsService analyticsService;
    private DataPlatformService dataPlatformService;

    public void completeOrder(Order order) {
        orderRepo.save(order);

        // 강한 결합 - 모든 서비스를 알아야 함
        rankingService.update(order);
        analyticsService.analyze(order);
        dataPlatformService.send(order);
    }
}
```

**After Kafka:**
```java
@Service
class OrderService {
    private KafkaTemplate kafkaTemplate;

    public void completeOrder(Order order) {
        orderRepo.save(order);

        // 느슨한 결합 - Kafka만 알면 됨
        kafkaTemplate.send("order-events", order);
    }
}

// 새로운 Consumer 추가 (코드 변경 없이!)
@Service
class MarketingService {
    @KafkaListener(topics = "order-events")
    public void handleOrder(OrderEvent event) {
        // 마케팅 캠페인 트리거
    }
}
```

#### ✅ 2. 확장성 & 성능

**수평 확장:**
```
단계 1: 브로커 3개, 파티션 3개
  - 처리량: 10,000 msg/sec
  - Consumer: 3개 병렬 처리

단계 2: 브로커 6개, 파티션 6개
  - 처리량: 20,000 msg/sec
  - Consumer: 6개 병렬 처리

단계 3: 브로커 12개, 파티션 12개
  - 처리량: 40,000 msg/sec
  - Consumer: 12개 병렬 처리
```

**실제 성능:**
- 초당 **수백만 메시지** 처리 가능
- LinkedIn: 하루 **1조 개** 메시지 처리
- Netflix: 하루 **7,000억 개** 이벤트 처리

#### ✅ 3. 데이터 재처리 가능

**시나리오 1: 버그 수정 후 재처리**
```
1. 랭킹 업데이트 로직에 버그 발견
2. 버그 수정
3. Offset을 7일 전으로 리셋
4. 과거 7일 데이터 재처리
```

**시나리오 2: 새로운 분석 시스템 추가**
```
1. 새로운 AI 추천 시스템 개발
2. 과거 30일 주문 데이터로 학습 필요
3. Kafka에서 30일 전부터 데이터 읽기
4. 모델 학습 완료
```

**vs 기존 방식:**
```
기존 (DB 기반):
  - 과거 데이터는 DB 쿼리로만 조회 가능
  - 실시간 이벤트 스트림 재현 불가
  - 새로운 Consumer 추가 시 과거 데이터 활용 어려움

Kafka:
  - 과거 데이터를 실시간 스트림처럼 재처리
  - 새로운 Consumer가 언제든 과거부터 읽기 가능
```

#### ✅ 4. 실시간 스트리밍

**실시간 데이터 파이프라인:**
```
주문 발생 (0ms)
  → Kafka 발행 (10ms)
    → 랭킹 업데이트 (50ms)
      → 사용자 화면 반영 (100ms)

총 지연시간: 100ms 이내
```

**이벤트 소싱 아키텍처:**
```
모든 상태 변경을 이벤트로 저장
  - 주문 생성 이벤트
  - 결제 완료 이벤트
  - 배송 시작 이벤트

이벤트를 재생하면 현재 상태 복원 가능
```

#### ✅ 5. 내결함성 (Fault Tolerance)

**브로커 장애 시:**
```
Broker 1 (Leader): 장애 발생 ❌
  ↓
Zookeeper가 감지
  ↓
Broker 2 (Replica)를 새로운 Leader로 선출
  ↓
서비스 계속 동작 ✅

데이터 손실: 없음 (복제되어 있음)
다운타임: 수 초 이내
```

**네트워크 장애 시:**
```
Producer → Broker 전송 실패
  ↓
Producer가 자동 재시도 (retries: 3)
  ↓
다른 Broker로 전송 시도
  ↓
성공 ✅
```

---

### 4.2 단점

#### ❌ 1. 운영 복잡도 증가

**관리해야 할 것들:**
```
1. 클러스터 관리
   - 브로커 상태 모니터링
   - 디스크 공간 관리
   - 파티션 리밸런싱

2. Zookeeper 관리 (또는 KRaft)
   - 별도 서버 필요
   - 고가용성 구성 (최소 3대)

3. Topic 관리
   - 파티션 수 결정
   - Replication Factor 설정
   - Retention 정책 설정

4. Consumer Group 관리
   - Lag 모니터링
   - 리밸런싱 최적화
```

**모니터링 필요 지표:**
```
Broker:
  - CPU, Memory, Disk 사용률
  - 네트워크 I/O
  - Under-replicated Partitions

Producer:
  - 발행 성공률
  - 발행 지연시간
  - 에러율

Consumer:
  - Consumer Lag (지연된 메시지 수)
  - 처리 처리량
  - Rebalance 빈도
```

#### ❌ 2. 학습 곡선

**배워야 할 개념:**
```
기본:
  - Broker, Topic, Partition
  - Producer, Consumer, Consumer Group
  - Offset, Replication

중급:
  - 파티셔닝 전략
  - Offset 관리 (Auto/Manual Commit)
  - Consumer Lag 모니터링
  - Rebalancing 이해

고급:
  - Exactly-Once Semantics
  - Transactional Producer
  - Kafka Streams
  - Schema Registry
```

#### ❌ 3. 인프라 비용

**최소 구성 (고가용성):**
```
Zookeeper: 3대 (or KRaft 사용)
Kafka Broker: 3대
  - CPU: 8 core
  - Memory: 32GB
  - Disk: 1TB SSD (각)

월 비용 (AWS 기준):
  - EC2: $500 ~ $1,000
  - EBS: $300 ~ $500
  - 총: $800 ~ $1,500/월

또는 Managed Service:
  - AWS MSK: $1,000 ~ $2,000/월
  - Confluent Cloud: $1,500 ~ $3,000/월
```

#### ❌ 4. 순서 보장 제한

**파티션 단위로만 순서 보장:**
```
문제 상황:
  - 사용자 A가 주문 생성 → 주문 취소
  - 주문 생성 이벤트: Partition 0
  - 주문 취소 이벤트: Partition 1 (다른 파티션)

결과:
  - Consumer가 취소 이벤트를 먼저 읽을 수 있음
  - 순서 보장 안 됨 ❌

해결책:
  - 같은 주문 ID를 Key로 사용
  - → 같은 파티션으로 전송
  - → 순서 보장 ✅
```

**전체 순서 보장이 필요한 경우:**
```
파티션을 1개만 사용
  ↓
병렬 처리 불가
  ↓
처리량 제한 (1개 Consumer만 가능)
```

#### ❌ 5. 작은 규모 시스템에는 과도할 수 있음

**Kafka가 과도한 경우:**
```
시나리오:
  - 초당 10 ~ 100 메시지
  - Consumer 1 ~ 2개
  - 재처리 필요 없음

더 나은 선택:
  - Redis Pub/Sub
  - RabbitMQ
  - AWS SQS
  - 단순 DB Queue
```

**Kafka가 적합한 경우:**
```
시나리오:
  - 초당 1,000+ 메시지
  - Consumer 3개 이상 (병렬 처리 필요)
  - 데이터 재처리 필요
  - 여러 시스템 간 데이터 동기화
  - 마이크로서비스 아키�ecture
```

---

## 5. 핵심 기능

### 5.1 메시지 발행 & 구독 (Pub/Sub)

**기본 흐름:**
```
Producer                 Kafka                  Consumer
   │                       │                       │
   ├─ send(msg) ──────────>│                       │
   │                       ├─ 디스크 저장          │
   │<──────── ack ─────────┤                       │
   │                       │                       │
   │                       │<───── poll() ─────────┤
   │                       ├─ msg ────────────────>│
   │                       │                       ├─ process()
   │                       │<───── commit() ───────┤
```

**코드 예시:**
```java
// Producer
@Service
class OrderEventProducer {
    @Autowired
    private KafkaTemplate<Long, OrderEvent> kafkaTemplate;

    public void publishOrderCompleted(OrderEvent event) {
        kafkaTemplate.send("order-events", event.getOrderId(), event);
    }
}

// Consumer
@Service
class RankingConsumer {
    @KafkaListener(topics = "order-events", groupId = "ranking-updater")
    public void consume(OrderEvent event) {
        System.out.println("Received: " + event);
        // 랭킹 업데이트 로직
    }
}
```

---

### 5.2 파티셔닝 & 병렬 처리

**Key 기반 파티셔닝:**
```java
// 같은 주문 ID는 같은 파티션으로
ProducerRecord<Long, OrderEvent> record = new ProducerRecord<>(
    "order-events",
    event.getOrderId(),  // Key ← 중요!
    event
);

파티션 결정:
  hash(orderId) % partition_count = partition_number

예시:
  orderId=12345 → hash(12345) % 3 = 0 → Partition 0
  orderId=12346 → hash(12346) % 3 = 1 → Partition 1
  orderId=12347 → hash(12347) % 3 = 2 → Partition 2
  orderId=12348 → hash(12348) % 3 = 0 → Partition 0
```

**병렬 처리:**
```
Topic: order-events (Partition 3개)

Consumer Group: ranking-updater
┌──────────────────────────────────────┐
│ Consumer 1 (Thread 1)                │
│   → Partition 0 처리                 │
│   → 초당 1,000 msg                   │
├──────────────────────────────────────┤
│ Consumer 2 (Thread 2)                │
│   → Partition 1 처리                 │
│   → 초당 1,000 msg                   │
├──────────────────────────────────────┤
│ Consumer 3 (Thread 3)                │
│   → Partition 2 처리                 │
│   → 초당 1,000 msg                   │
└──────────────────────────────────────┘

총 처리량: 3,000 msg/sec
```

---

### 5.3 Offset 관리

**Offset 저장 위치:**
```
Kafka 내부 Topic: __consumer_offsets

저장 내용:
  - Consumer Group ID
  - Topic Name
  - Partition Number
  - Offset

예시:
  ranking-updater, order-events, 0, 12345
  ranking-updater, order-events, 1, 12346
  ranking-updater, order-events, 2, 12347
```

**Auto Commit vs Manual Commit:**
```java
// Auto Commit
@KafkaListener(topics = "order-events")
public void consume(OrderEvent event) {
    process(event);
    // 자동으로 5초마다 커밋 (설정에 따라)
}

문제점:
  - process(event) 실패 시에도 커밋될 수 있음
  - 메시지 손실 가능

// Manual Commit
@KafkaListener(topics = "order-events")
public void consume(OrderEvent event, Acknowledgment ack) {
    try {
        process(event);
        ack.acknowledge();  // 성공 시에만 커밋
    } catch (Exception ex) {
        // 커밋 안 함 → 재처리
    }
}

장점:
  - 안전성 높음
  - 처리 실패 시 재처리 보장
```

**Offset 리셋 (재처리):**
```bash
# 1. 처음부터 다시 읽기
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group ranking-updater \
  --reset-offsets --to-earliest \
  --topic order-events \
  --execute

# 2. 특정 Offset으로 이동
--reset-offsets --to-offset 10000

# 3. 특정 시간으로 이동
--reset-offsets --to-datetime 2025-12-01T00:00:00.000

# 4. 최신으로 이동 (메시지 스킵)
--reset-offsets --to-latest
```

---

### 5.4 메시지 보관 (Retention)

**Retention 정책:**
```yaml
# 시간 기반 (Time-based)
retention.ms: 604800000  # 7일 (밀리초)

동작:
  - 메시지 생성 후 7일 경과 시 삭제
  - 용도: 단기 이벤트 스트림 (주문, 로그 등)

# 크기 기반 (Size-based)
retention.bytes: 1073741824  # 1GB

동작:
  - 파티션 크기가 1GB 초과 시 오래된 메시지 삭제
  - 용도: 디스크 공간 제한 시

# 무한 보관 (Compaction)
cleanup.policy: compact

동작:
  - Key별로 최신 메시지만 유지
  - 오래된 메시지는 삭제 (Key 기반)
  - 용도: 상태 변경 이벤트 (사용자 프로필, 상품 정보 등)
```

**Compaction 예시:**
```
Before Compaction:
  [userId=1, name="Alice"], [userId=2, name="Bob"], [userId=1, name="Alice Kim"]

After Compaction:
  [userId=2, name="Bob"], [userId=1, name="Alice Kim"]

→ userId=1의 최신 값만 유지
```

---

### 5.5 Exactly-Once Semantics (EOS)

**메시지 전달 보장 수준:**
```
1. At-Most-Once (최대 한 번)
   - 메시지가 손실될 수 있음
   - 중복은 없음
   - 예: 로그 수집

2. At-Least-Once (최소 한 번)
   - 메시지 손실 없음
   - 중복 가능
   - 예: 대부분의 시스템 (멱등성 보장 필요)

3. Exactly-Once (정확히 한 번)
   - 메시지 손실 없음
   - 중복 없음
   - 예: 금융 거래, 결제
```

**Idempotent Producer (중복 방지):**
```yaml
Producer Config:
  enable.idempotence: true

동작 원리:
  1. Producer가 각 메시지에 고유 ID 부여
  2. Broker가 중복 메시지 감지
  3. 중복 메시지는 저장하지 않음 (Ack만 전송)

결과:
  - 네트워크 재시도 시 중복 방지
  - 안전하게 재시도 가능
```

**Transactional Producer (원자성 보장):**
```java
// 여러 메시지를 하나의 트랜잭션으로 전송
producer.initTransactions();

try {
    producer.beginTransaction();

    producer.send(new ProducerRecord<>("order-events", order));
    producer.send(new ProducerRecord<>("payment-events", payment));

    producer.commitTransaction();  // 모두 성공
} catch (Exception ex) {
    producer.abortTransaction();  // 모두 롤백
}

Consumer 설정:
  isolation.level: read_committed
  → 커밋된 메시지만 읽음
```

---

## 6. 이벤트 기반 아키텍처 확장

### 6.1 이벤트 아이디어를 시스템 전체로 확장

**단일 서비스 → 마이크로서비스:**
```
Before: Monolithic
┌────────────────────────────────┐
│      E-commerce Service        │
│                                │
│  - 주문 관리                   │
│  - 회원 관리                   │
│  - 상품 관리                   │
│  - 결제 관리                   │
│  - 배송 관리                   │
└────────────────────────────────┘

문제점:
  - 하나의 기능 장애가 전체 영향
  - 독립적인 배포 불가
  - 확장 어려움

After: Microservices + Kafka
┌──────────┐   ┌──────────┐   ┌──────────┐
│  주문    │   │  회원    │   │  상품    │
│ 서비스   │   │ 서비스   │   │ 서비스   │
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
│  결제    │   │  배송    │   │  알림    │
│ 서비스   │   │ 서비스   │   │ 서비스   │
└──────────┘   └──────────┘   └──────────┘

장점:
  - 독립적인 배포/확장
  - 기술 스택 자유
  - 장애 격리
```

---

### 6.2 실시간 데이터 파이프라인

**CDC (Change Data Capture) + Kafka:**
```
┌──────────────┐
│  MySQL DB    │
│              │
│  orders 테이블│
│  - INSERT    │
│  - UPDATE    │
│  - DELETE    │
└──────┬───────┘
       │
       │ Debezium (CDC Connector)
       ▼
┌──────────────┐
│    Kafka     │
│              │
│  db.orders   │
│  - before    │
│  - after     │
└──────┬───────┘
       │
   ┌───┼───┐
   │   │   │
   ▼   ▼   ▼
┌────┐┌────┐┌────┐
│ES  ││DW  ││S3  │
└────┘└────┘└────┘

용도:
  - 실시간 검색 엔진 동기화
  - 데이터 웨어하우스 적재
  - 데이터 레이크 백업
```

---

### 6.3 CQRS (Command Query Responsibility Segregation)

**명령과 조회의 분리:**
```
┌─────────────────────────────────────┐
│         Command Side (쓰기)          │
│                                     │
│  Order Service (Write DB)           │
│    → 주문 생성/수정/삭제            │
└──────────────┬──────────────────────┘
               │
               │ Kafka
               ▼
┌─────────────────────────────────────┐
│          Query Side (읽기)           │
│                                     │
│  - Order View Service (Read DB)     │
│  - Elasticsearch (검색)              │
│  - Redis (캐시)                     │
└─────────────────────────────────────┘

장점:
  - 쓰기 최적화 (정규화 DB)
  - 읽기 최적화 (역정규화, 캐시)
  - 독립적인 확장
```

---

### 6.4 Event Sourcing

**모든 상태 변경을 이벤트로 저장:**
```
기존 방식 (State-based):
  orders 테이블
  ┌────┬──────┬────────┐
  │ ID │ 상태 │  총액  │
  ├────┼──────┼────────┤
  │ 1  │ 완료 │ 50000  │
  └────┴──────┴────────┘

  문제점:
    - 과거 이력 손실 (UPDATE로 덮어씀)
    - 상태 변경 원인 불명확

Event Sourcing:
  order_events 토픽
  ┌─────────────────────────────────┐
  │ OrderCreated (orderId=1, ...)   │
  │ PaymentCompleted (orderId=1)    │
  │ ShippingStarted (orderId=1)     │
  │ OrderCompleted (orderId=1)      │
  └─────────────────────────────────┘

  장점:
    - 완전한 이력 보존
    - 이벤트 재생으로 상태 복원 가능
    - 감사(Audit) 로그
```

---

### 6.5 Saga Pattern (분산 트랜잭션)

**마이크로서비스 간 트랜잭션:**
```
주문 프로세스:
  1. 주문 생성
  2. 재고 차감
  3. 결제 처리
  4. 배송 시작

Choreography Saga (Kafka 이용):
┌──────────┐      ┌──────────┐      ┌──────────┐
│  주문    │      │  재고    │      │  결제    │
│ 서비스   │      │ 서비스   │      │ 서비스   │
└─────┬────┘      └─────┬────┘      └─────┬────┘
      │                 │                 │
      ├─OrderCreated──> │                 │
      │                 ├─StockReserved─> │
      │                 │                 ├─PaymentCompleted
      │<────────────────┴─────────────────┘

실패 시 보상 트랜잭션:
  결제 실패 → StockReleased → OrderCancelled
```

---

## 7. 학습 요약

### 7.1 핵심 개념 체크리스트

- [ ] Kafka는 분산 이벤트 스트리밍 플랫폼이다
- [ ] Broker는 Kafka 서버, Topic은 메시지 카테고리다
- [ ] Partition은 순서 보장과 병렬 처리를 위한 분할 단위다
- [ ] Consumer Group을 통해 파티션을 분산 처리한다
- [ ] Offset은 Consumer가 읽은 위치를 나타낸다
- [ ] Replication으로 데이터 안정성을 보장한다
- [ ] Key 기반 파티셔닝으로 순서를 보장할 수 있다

### 7.2 장단점 이해

**장점:**
- ✅ 느슨한 결합 (Decoupling)
- ✅ 수평 확장 & 고성능
- ✅ 데이터 재처리 가능
- ✅ 실시간 스트리밍
- ✅ 내결함성

**단점:**
- ❌ 운영 복잡도 증가
- ❌ 학습 곡선
- ❌ 인프라 비용
- ❌ 순서 보장 제한 (파티션 단위)

### 7.3 언제 Kafka를 사용할까?

**적합한 경우:**
- 초당 1,000+ 메시지
- 여러 Consumer가 같은 데이터를 독립적으로 처리
- 데이터 재처리 필요
- 마이크로서비스 아키텍처
- 실시간 데이터 파이프라인

**부적합한 경우:**
- 초당 10 ~ 100 메시지
- Consumer 1 ~ 2개
- 간단한 Job Queue
- Request-Response 패턴

---

## 8. 다음 단계

### 8.1 실습 권장 순서

1. **Docker로 Kafka 실행**
   ```bash
   docker-compose -f docker-compose.kafka.yaml up -d
   ```

2. **콘솔로 메시지 발행/구독**
   ```bash
   # Producer
   kafka-console-producer --topic test --bootstrap-server localhost:9092

   # Consumer
   kafka-console-consumer --topic test --from-beginning --bootstrap-server localhost:9092
   ```

3. **Spring Kafka 통합**
   - KafkaTemplate으로 메시지 발행
   - @KafkaListener로 메시지 구독

4. **Outbox Pattern + Kafka**
   - 트랜잭션 안정성 보장
   - 재시도 메커니즘

---

## 9. 참고 자료

### 9.1 공식 문서
- [Apache Kafka Documentation](https://kafka.apache.org/documentation/)
- [Spring Kafka Reference](https://spring.io/projects/spring-kafka)

### 9.2 추천 학습 자료
- [Confluent Kafka Tutorials](https://kafka-tutorials.confluent.io/)
- [Martin Kleppmann - Designing Data-Intensive Applications](https://dataintensive.net/)

### 9.3 모니터링 도구
- Kafka Manager / CMAK
- Confluent Control Center
- Kafka Exporter + Prometheus + Grafana

---

## 결론

Kafka는 현대적인 이벤트 기반 아키텍처의 핵심 기술입니다.

**우리 프로젝트에서 Kafka를 도입하면:**
- 주문, 쿠폰, 회원 등 모든 도메인 이벤트를 통합 관리
- 마이크로서비스로의 전환 준비
- 실시간 분석 및 데이터 파이프라인 구축

**하지만 단계적으로 접근해야 합니다:**
1. 로컬 환경에서 학습
2. 단일 Topic으로 시작 (order-events)
3. 점진적으로 확장 (coupon-events, user-events 등)

함께 Kafka를 학습하고 적용해봅시다! 🚀
