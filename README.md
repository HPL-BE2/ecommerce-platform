# 🛍️ E-Commerce Platform

이벤트 기반 아키텍처를 적용한 확장 가능한 이커머스 플랫폼입니다.

<img width="608" height="291" alt="image" src="https://github.com/user-attachments/assets/b488855a-db89-49e8-b4b0-d3f663df4b58" />

## 📌 프로젝트 개요

Spring Boot 3 기반의 **프로덕션 레벨 이커머스 API 플랫폼**으로, MSA(Microservices Architecture) 전환을 위한 이벤트 기반 설계를 적용했습니다.

### 핵심 특징

- ✅ **헥사고날 아키텍처** - Ports & Adapters 패턴으로 도메인과 인프라 계층 분리
- ✅ **이벤트 기반 설계** - Kafka를 활용한 비동기 메시징 및 이벤트 스트리밍
- ✅ **분산 시스템 패턴** - Outbox 패턴, Saga 패턴, 멱등성 보장
- ✅ **동시성 제어** - Redis 분산 락, 낙관적/비관적 락
- ✅ **다층 캐싱** - Redis 캐시를 활용한 성능 최적화
- ✅ **확장 가능한 설계** - 커서 기반 페이징, Kafka 파티셔닝

---

## 🏗️ 시스템 아키텍처

### 전체 구성도

```mermaid
graph TB
    subgraph "Client Layer"
        Client[클라이언트<br/>Web/Mobile]
    end

    subgraph "Application Layer"
        API[REST API<br/>Controllers]
        Service[Application<br/>Services]
        Domain[Domain<br/>Models]
    end

    subgraph "Infrastructure Layer"
        JPA[JPA<br/>Adapters]
        RedisAdapter[Redis<br/>Client]
        KafkaAdapter[Kafka<br/>Producer]
    end

    subgraph "Data Layer"
        MySQL[(MySQL 8.0<br/>주문/상품/지갑)]
        Redis[(Redis 7.0<br/>캐시/락/랭킹)]
    end

    subgraph "Messaging Layer"
        Kafka[Apache Kafka<br/>3-Broker Cluster]
        Consumer1[Ranking<br/>Updater]
        Consumer2[Data<br/>Platform]
        Consumer3[Coupon<br/>Issuer]
    end

    Client --> API
    API --> Service
    Service --> Domain
    Domain --> JPA
    Domain --> RedisAdapter
    Domain --> KafkaAdapter

    JPA --> MySQL
    RedisAdapter --> Redis
    KafkaAdapter --> Kafka

    Kafka --> Consumer1
    Kafka --> Consumer2
    Kafka --> Consumer3

    Consumer1 --> Redis
    Consumer3 --> MySQL

    style Client fill:#e1f5ff
    style API fill:#fff4e1
    style Service fill:#ffe1f5
    style Domain fill:#f0e1ff
    style MySQL fill:#e1ffe1
    style Redis fill:#ffe1e1
    style Kafka fill:#fff9e1
```

### 헥사고날 아키텍처 구조

```mermaid
graph LR
    subgraph "Inbound Adapters"
        REST[REST<br/>Controllers]
        Kafka_In[Kafka<br/>Consumers]
    end

    subgraph "Application Core"
        Ports_In[Inbound<br/>Ports]
        Services[Application<br/>Services]
        Domain[Domain<br/>Models]
        Ports_Out[Outbound<br/>Ports]
    end

    subgraph "Outbound Adapters"
        JPA[JPA<br/>Repositories]
        Redis[Redis<br/>Client]
        Kafka_Out[Kafka<br/>Producers]
    end

    REST --> Ports_In
    Kafka_In --> Ports_In
    Ports_In --> Services
    Services --> Domain
    Services --> Ports_Out
    Ports_Out --> JPA
    Ports_Out --> Redis
    Ports_Out --> Kafka_Out

    style REST fill:#e1f5ff
    style Kafka_In fill:#e1f5ff
    style Services fill:#ffe1f5
    style Domain fill:#f0e1ff
    style JPA fill:#e1ffe1
    style Redis fill:#ffe1e1
    style Kafka_Out fill:#fff9e1
```

---

## 📡 주요 API 엔드포인트

| 메서드 | 엔드포인트 | 설명 | 주요 기능 |
|--------|-----------|------|----------|
| GET | `/api/v1/products` | 상품 목록 조회 | 커서 기반 페이징, 검색, Redis 캐싱 (3분) |
| GET | `/api/v1/products/{id}` | 상품 상세 조회 | Redis 캐싱 (10분) |
| POST | `/api/v1/wallets/{userId}/topups` | 지갑 충전 | 멱등성 보장, 비관적 락 |
| POST | `/api/v1/orders` | 주문 생성 | 재고 검증, 쿠폰 적용, 분산 락 |
| PATCH | `/api/v1/orders/{orderId}/complete` | 주문 완료 | 이벤트 발행 (Kafka) |
| POST | `/coupons/{id}/issue` | 쿠폰 발급 (동기) | 분산 락, Redis 원자적 카운터 |
| POST | `/coupons/{id}/issue-async` | 쿠폰 발급 (비동기) | Kafka 기반, Lua 스크립트 |
| GET | `/api/v1/rankings/products` | 상품 랭킹 조회 | Redis Sorted Set, 실시간/일별/주간 |

---

## 🔄 API 시퀀스 다이어그램

각 API의 상세한 처리 흐름을 확인하려면 아래 항목을 클릭하세요.

<details>
<summary><b>📦 상품 목록 조회 API</b> - GET /api/v1/products</summary>

### 상품 목록 조회 플로우
- Redis 캐시 우선 조회 (3분 TTL)
- 캐시 미스 시 DB 조회 후 캐싱
- 커서 기반 페이징으로 대용량 데이터 처리

```mermaid
sequenceDiagram
    participant Client as 클라이언트
    participant Controller as ProductsController
    participant Service as ProductService
    participant Redis as Redis Cache
    participant DB as MySQL

    Client->>Controller: GET /api/v1/products?q=검색어&limit=20
    Controller->>Service: getProducts(query, limit, cursor)
    Service->>Redis: 캐시 조회 (products:key)

    alt 캐시 히트
        Redis-->>Service: 캐시된 데이터 반환
    else 캐시 미스
        Service->>DB: SELECT * FROM products WHERE...
        DB-->>Service: 상품 목록
        Service->>Redis: 캐시 저장 (TTL: 3분)
    end

    Service-->>Controller: ProductListResponse
    Controller-->>Client: 200 OK + 상품 목록
```

</details>

<details>
<summary><b>📦 상품 상세 조회 API</b> - GET /api/v1/products/{id}</summary>

### 상품 상세 조회 플로우
- Redis 캐시 우선 조회 (10분 TTL)
- 상품 정보 + 가격 정보 포함

```mermaid
sequenceDiagram
    participant Client as 클라이언트
    participant Controller as ProductsController
    participant Service as ProductService
    participant Redis as Redis Cache
    participant DB as MySQL

    Client->>Controller: GET /api/v1/products/{productId}
    Controller->>Service: getProductDetail(productId)
    Service->>Redis: 캐시 조회 (product-detail:{id})

    alt 캐시 히트
        Redis-->>Service: 캐시된 상품 정보
    else 캐시 미스
        Service->>DB: SELECT * FROM products WHERE id=?
        DB-->>Service: 상품 상세 정보
        Service->>Redis: 캐시 저장 (TTL: 10분)
    end

    Service-->>Controller: ProductDetailResponse
    Controller-->>Client: 200 OK + 상품 상세
```

</details>

<details>
<summary><b>💰 지갑 충전 API</b> - POST /api/v1/wallets/{userId}/topups</summary>

### 지갑 충전 플로우
- 멱등성 보장 (Idempotency-Key 헤더)
- 비관적 락(SELECT FOR UPDATE)을 통한 동시성 제어
- 거래 내역 추적 및 잔액 오버플로우 방지

```mermaid
sequenceDiagram
    participant Client as 클라이언트
    participant Controller as WalletController
    participant Service as WalletService
    participant DB as MySQL

    Client->>Controller: POST /api/v1/wallets/{userId}/topups<br/>Header: Idempotency-Key<br/>Body: {amount, refType, refId}
    Controller->>Service: topup(userId, amount, idempotencyKey)

    Service->>DB: 멱등성 체크<br/>SELECT * FROM wallet_transactions<br/>WHERE user_id=? AND idempotency_key=?

    alt 이미 처리된 요청
        DB-->>Service: 기존 거래 내역
        Service-->>Controller: {idempotent: true, 기존 결과}
    else 신규 요청
        Service->>DB: BEGIN TRANSACTION
        Service->>DB: 비관적 락 획득<br/>SELECT * FROM wallets<br/>WHERE user_id=? FOR UPDATE
        DB-->>Service: Wallet 정보

        Service->>Service: 잔액 계산 (Math.addExact)<br/>오버플로우 검증

        Service->>DB: INSERT INTO wallet_transactions
        Service->>DB: UPDATE wallets SET balance = balance + ?
        Service->>DB: COMMIT

        DB-->>Service: 업데이트 완료
        Service-->>Controller: {idempotent: false, 새 잔액}
    end

    Controller-->>Client: 200 OK + 거래 결과
```

</details>

<details>
<summary><b>🛒 주문 생성 API</b> - POST /api/v1/orders</summary>

### 주문 생성 플로우
- 병렬 검증: 재고, 쿠폰, 지갑 잔액
- Redis 분산 락 기반 재고 예약
- 실패 시 보상 트랜잭션 실행 (재고 복원, 쿠폰 해제, 지갑 환불)

```mermaid
sequenceDiagram
    participant Client as 클라이언트
    participant Controller as OrderController
    participant OrderService as OrderService
    participant InventoryService as InventoryService
    participant CouponService as CouponService
    participant WalletService as WalletService
    participant Redis as Redis
    participant DB as MySQL

    Client->>Controller: POST /api/v1/orders<br/>Header: Idempotency-Key<br/>Body: {userId, items, couponCode, expectedTotal}

    Controller->>OrderService: createOrder(request)

    OrderService->>DB: 멱등성 체크<br/>SELECT * FROM orders WHERE request_key=?

    alt 이미 처리된 주문
        DB-->>OrderService: 기존 주문 정보
        OrderService-->>Controller: 기존 주문 반환
    else 신규 주문

        par 병렬 검증
            OrderService->>InventoryService: validateStock(items)
            InventoryService->>Redis: 재고 캐시 조회
            InventoryService->>DB: SELECT stock FROM inventory
            InventoryService-->>OrderService: 재고 OK
        and
            OrderService->>CouponService: validateCoupon(couponCode, userId)
            CouponService->>DB: 쿠폰 유효성 검증
            CouponService-->>OrderService: 쿠폰 정보 + 할인액
        and
            OrderService->>WalletService: getBalance(userId)
            WalletService->>DB: SELECT balance FROM wallets
            WalletService-->>OrderService: 잔액 정보
        end

        OrderService->>OrderService: 총액 계산 및 검증<br/>(expectedTotal 매칭)

        OrderService->>DB: BEGIN TRANSACTION

        loop 각 상품별
            OrderService->>Redis: 분산 락 획득<br/>product:{id}:order:lock
            OrderService->>InventoryService: reserve(productId, qty)
            InventoryService->>DB: UPDATE inventory<br/>SET stock = stock - ?<br/>WHERE version = ? (낙관적 락)
            alt 낙관적 락 충돌
                DB-->>InventoryService: 업데이트 실패
                InventoryService->>InventoryService: 재시도 (3회, 지수 백오프)
            end
            OrderService->>Redis: 락 해제
        end

        OrderService->>CouponService: useCoupon(couponCode, userId)
        CouponService->>DB: UPDATE coupon_issuances SET used=true

        OrderService->>WalletService: debit(userId, total)
        WalletService->>DB: SELECT * FROM wallets FOR UPDATE
        WalletService->>DB: UPDATE wallets SET balance = balance - ?

        OrderService->>DB: INSERT INTO orders
        OrderService->>DB: INSERT INTO order_items

        OrderService->>DB: COMMIT

        OrderService-->>Controller: 주문 생성 완료
    end

    Controller-->>Client: 201 Created + 주문 정보

    Note over Client,DB: 실패 시 보상 트랜잭션 실행<br/>(재고 복원, 쿠폰 해제, 지갑 환불)
```

</details>

<details>
<summary><b>✅ 주문 완료 API</b> - PATCH /api/v1/orders/{orderId}/complete</summary>

### 주문 완료 및 이벤트 발행 플로우
- Spring Application Event로 즉시 발행
- Outbox 패턴으로 이벤트 영속성 보장
- Kafka로 다중 컨슈머에게 전달 (랭킹 업데이트, 데이터 플랫폼)

```mermaid
sequenceDiagram
    participant Client as 클라이언트
    participant Controller as OrderController
    participant OrderService as OrderService
    participant DB as MySQL
    participant EventBus as Spring Event Bus
    participant RankingListener as RankingListener
    participant DataListener as DataListener
    participant OutboxDispatcher as OutboxDispatcher
    participant Kafka as Kafka

    Client->>Controller: PATCH /api/v1/orders/{orderId}/complete

    Controller->>OrderService: complete(orderId)

    OrderService->>DB: BEGIN TRANSACTION
    OrderService->>DB: UPDATE orders<br/>SET status='COMPLETED', completed_at=NOW()<br/>WHERE order_id=? AND status='RESERVED'

    OrderService->>DB: INSERT INTO outbox_events<br/>(event_type, payload, status='PENDING')

    OrderService->>DB: COMMIT

    OrderService->>EventBus: publish(OrderCompletedDomainEvent)

    par 이벤트 리스너 병렬 처리
        EventBus->>RankingListener: onOrderCompleted(event)
        RankingListener->>Kafka: produce(ecommerce.order.events)<br/>Key: orderId
        Kafka-->>RankingListener: ACK

    and
        EventBus->>DataListener: onOrderCompleted(event)
        DataListener->>Kafka: produce(ecommerce.order.events)<br/>데이터 플랫폼용
        Kafka-->>DataListener: ACK

    and
        EventBus->>OutboxDispatcher: onOrderCompleted(event)
        Note over OutboxDispatcher: 이미 DB에 저장됨<br/>스케줄러가 5초마다 폴링
    end

    OrderService-->>Controller: 완료 응답
    Controller-->>Client: 200 OK + 주문 완료 정보

    Note over OutboxDispatcher,Kafka: 별도 스케줄러 (5초마다)
    OutboxDispatcher->>DB: SELECT * FROM outbox_events<br/>WHERE status='PENDING' LIMIT 20
    DB-->>OutboxDispatcher: 대기 중인 이벤트들

    loop 각 이벤트 (배치 20개)
        OutboxDispatcher->>Kafka: produce(토픽, 이벤트)
        alt Kafka 발행 성공
            Kafka-->>OutboxDispatcher: ACK
            OutboxDispatcher->>DB: UPDATE outbox_events SET status='SENT'
        else Kafka 발행 실패
            Kafka-->>OutboxDispatcher: ERROR
            OutboxDispatcher->>DB: UPDATE retry_count++, next_retry_at=...
        end
    end
```

</details>

<details>
<summary><b>🎟️ 쿠폰 발급 API (동기)</b> - POST /coupons/{id}/issue</summary>

### 동기 쿠폰 발급 플로우
- Redis 분산 락으로 동시 요청 제어
- Redis INCR로 원자적 카운터 증가
- 선착순 제한 검증 및 DB 저장

```mermaid
sequenceDiagram
    participant Client as 클라이언트
    participant Controller as CouponController
    participant Service as CouponService
    participant Redis as Redis
    participant DB as MySQL

    Client->>Controller: POST /coupons/{couponId}/issue?userId={userId}

    Controller->>Service: issueCoupon(couponId, userId)

    Service->>Redis: 분산 락 획득<br/>coupon:{couponId}:lock<br/>waitTime: 2s, leaseTime: 5s

    alt 락 획득 실패
        Redis-->>Service: 락 타임아웃
        Service-->>Controller: 429 Too Many Requests
        Controller-->>Client: 잠시 후 다시 시도
    else 락 획득 성공

        Service->>Redis: INCR coupon:{couponId}:issued
        Redis-->>Service: 현재 발급 수

        Service->>Service: 발급 한도 검증<br/>(issued <= maxIssuance)

        alt 발급 한도 초과
            Service->>Redis: DECR coupon:{couponId}:issued
            Service->>Redis: 락 해제
            Service-->>Controller: 409 Conflict - 쿠폰 소진
        else 발급 가능
            Service->>DB: BEGIN TRANSACTION

            Service->>DB: SELECT * FROM coupons WHERE coupon_id=?
            DB-->>Service: 쿠폰 정보

            Service->>Service: 유효성 검증<br/>(유효 기간, 중복 발급 체크)

            Service->>DB: INSERT INTO coupon_issuances<br/>(coupon_id, user_id, issued_at)

            Service->>DB: COMMIT

            Service->>Redis: 락 해제
            Service-->>Controller: 발급 성공
        end
    end

    Controller-->>Client: 200 OK + 쿠폰 발급 정보
```

</details>

<details>
<summary><b>🎟️ 쿠폰 발급 API (비동기)</b> - POST /coupons/{id}/issue-async</summary>

### 비동기 쿠폰 발급 플로우 (Kafka)
- Kafka Producer로 즉시 응답 (202 Accepted)
- Kafka Consumer가 비동기로 처리
- Redis Lua Script로 원자적 검증 및 증가
- 결과는 별도 토픽으로 전송

```mermaid
sequenceDiagram
    participant Client as 클라이언트
    participant Controller as CouponController
    participant Producer as CouponKafkaProducer
    participant Redis as Redis
    participant Kafka as Kafka
    participant Consumer as CouponKafkaConsumer
    participant Service as AsyncCouponService
    participant DB as MySQL

    Client->>Controller: POST /coupons/{couponId}/issue-async?userId={userId}

    Controller->>Producer: sendIssueRequest(couponId, userId)

    Producer->>Producer: requestId 생성 (UUID)

    Producer->>Kafka: produce(coupon.issue.requests)<br/>Key: couponId<br/>Value: {requestId, couponId, userId}

    Kafka-->>Producer: ACK (Idempotent Producer)

    Producer-->>Controller: requestId 반환
    Controller-->>Client: 202 Accepted<br/>{requestId, message: "처리 중"}

    Note over Kafka,Consumer: Kafka Consumer (3 threads)

    Kafka->>Consumer: poll(coupon.issue.requests)
    Consumer->>Service: processIssueRequest(couponId, userId)

    Service->>Redis: Lua Script 실행<br/>원자적 검증 및 INCR

    alt Lua Script - 발급 가능
        Redis-->>Service: SUCCESS + issued count

        Service->>DB: BEGIN TRANSACTION
        Service->>DB: INSERT INTO coupon_issuances<br/>(coupon_id, user_id, request_id, ...)
        Service->>DB: COMMIT

        Service->>Kafka: produce(coupon.issue.results)<br/>{requestId, status: SUCCESS}

        Service->>Consumer: manual ACK
        Consumer->>Kafka: commit offset

    else Lua Script - 발급 불가
        Redis-->>Service: FAIL - 발급 한도 초과

        Service->>Kafka: produce(coupon.issue.results)<br/>{requestId, status: FAILED, reason: SOLD_OUT}

        Service->>Consumer: manual ACK
        Consumer->>Kafka: commit offset
    end

    Note over Client: 클라이언트는 폴링 또는<br/>WebSocket으로 결과 수신
```

</details>

<details>
<summary><b>🏆 상품 랭킹 조회 API</b> - GET /api/v1/rankings/products</summary>

### 상품 랭킹 조회 플로우
- Redis Sorted Set 조회 (O(log n) 성능)
- 실시간/일별/주간 랭킹 지원
- Graceful degradation (Redis 장애 시 빈 결과)

```mermaid
sequenceDiagram
    participant Client as 클라이언트
    participant Controller as RankingController
    participant Service as ProductRankingService
    participant Redis as Redis Sorted Set
    participant DB as MySQL

    Client->>Controller: GET /api/v1/rankings/products?period=DAILY&limit=10

    Controller->>Service: getTopProducts(period, referenceDate, limit)

    Service->>Service: ranking key 생성<br/>예: ranking:daily:2025-12-14

    Service->>Redis: ZREVRANGE ranking:daily:2025-12-14 0 9<br/>WITHSCORES

    alt Redis 데이터 존재
        Redis-->>Service: [(productId, score), ...]

        Service->>DB: SELECT id, name, thumbnail_url, unit_price<br/>FROM products WHERE id IN (?, ?, ...)

        DB-->>Service: 상품 정보 목록

        Service->>Service: score와 상품 정보 매핑
        Service-->>Controller: RankingResponse<br/>{items: [{productId, name, score}, ...]}

    else Redis 데이터 없음
        Redis-->>Service: 빈 배열
        Service-->>Controller: 빈 랭킹 (graceful degradation)
    end

    Controller-->>Client: 200 OK + 랭킹 정보

    Note over Redis: 랭킹 업데이트는<br/>주문 완료 이벤트 수신 시<br/>ZINCRBY로 실행
```

</details>

---

## 💾 데이터베이스 설계

<details>
<summary><b>📊 ERD (Entity Relationship Diagram)</b></summary>

### 데이터베이스 ERD

```mermaid
erDiagram
    USERS ||--|| WALLETS : has
    USERS ||--o{ WALLET_TRANSACTIONS : creates
    USERS ||--o{ ORDERS : places
    USERS ||--o{ COUPON_ISSUANCES : receives

    PRODUCTS ||--|| INVENTORY : has
    PRODUCTS ||--o{ ORDER_ITEMS : contains
    PRODUCTS ||--o{ STOCK_MOVEMENTS : tracks

    ORDERS ||--|{ ORDER_ITEMS : contains
    ORDERS ||--o| PAYMENTS : has

    COUPONS ||--o{ COUPON_ISSUANCES : issues

    USERS {
        bigint user_id PK
        string username
        string email
        timestamp created_at
    }

    WALLETS {
        bigint wallet_id PK
        bigint user_id FK
        bigint balance
        timestamp updated_at
    }

    WALLET_TRANSACTIONS {
        bigint transaction_id PK
        bigint user_id FK
        string transaction_type
        bigint amount
        bigint balance_after
        string idempotency_key UK
        timestamp created_at
    }

    PRODUCTS {
        bigint product_id PK
        string sku UK
        string name
        decimal unit_price
        string thumbnail_url
        timestamp created_at
    }

    INVENTORY {
        bigint inventory_id PK
        bigint product_id FK
        int stock
        int version
        timestamp updated_at
    }

    STOCK_MOVEMENTS {
        bigint movement_id PK
        bigint product_id FK
        string movement_type
        int quantity
        string reference_type
        bigint reference_id
        timestamp created_at
    }

    ORDERS {
        bigint order_id PK
        bigint user_id FK
        string status
        decimal subtotal
        decimal discount
        decimal total
        string request_key UK
        timestamp completed_at
        timestamp created_at
    }

    ORDER_ITEMS {
        bigint order_item_id PK
        bigint order_id FK
        bigint product_id FK
        string product_name
        decimal unit_price
        int quantity
        decimal line_total
    }

    PAYMENTS {
        bigint payment_id PK
        bigint order_id FK
        string payment_method
        decimal amount
        string status
        timestamp created_at
    }

    COUPONS {
        bigint coupon_id PK
        string code UK
        string discount_type
        decimal discount_value
        decimal min_purchase_amount
        decimal max_discount_amount
        int max_issuance
        timestamp starts_at
        timestamp ends_at
    }

    COUPON_ISSUANCES {
        bigint issuance_id PK
        bigint coupon_id FK
        bigint user_id FK
        boolean used
        timestamp issued_at
        timestamp used_at
    }
```

### 주요 테이블 설명

- **USERS**: 사용자 계정 정보
- **WALLETS**: 사용자별 지갑 잔액 (1:1)
- **WALLET_TRANSACTIONS**: 지갑 거래 내역 (충전/차감), 멱등성 키 포함
- **PRODUCTS**: 상품 카탈로그
- **INVENTORY**: 재고 관리 (낙관적 락 version 필드)
- **STOCK_MOVEMENTS**: 재고 변동 이력 (감사 추적)
- **ORDERS**: 주문 정보 (request_key로 멱등성 보장)
- **ORDER_ITEMS**: 주문 상품 목록
- **COUPONS**: 쿠폰 정의 (할인율, 한도, 유효기간)
- **COUPON_ISSUANCES**: 사용자별 쿠폰 발급 내역

</details>

---

## 📨 Kafka 이벤트 아키텍처

<details>
<summary><b>🔄 Kafka 토픽 및 컨슈머 구조</b></summary>

### Kafka 토픽 및 컨슈머 그룹

```mermaid
graph TB
    subgraph "Producers"
        OrderService[OrderService<br/>주문 완료]
        OutboxDispatcher[OutboxDispatcher<br/>Outbox 폴링]
        CouponProducer[CouponKafkaProducer<br/>쿠폰 발급 요청]
    end

    subgraph "Kafka Cluster (3 Brokers)"
        Topic1[ecommerce.order.events<br/>파티션: 3개<br/>리플리케이션: 1]
        Topic2[coupon.issue.requests<br/>파티션: 3개<br/>리플리케이션: 1]
        Topic3[coupon.issue.results<br/>파티션: 3개<br/>리플리케이션: 1]
    end

    subgraph "Consumer Groups"
        RankingConsumer[Ranking Updater<br/>그룹: ranking-updater<br/>스레드: 3개]
        DataConsumer[Data Platform<br/>그룹: data-platform<br/>스레드: 3개]
        CouponConsumer[Coupon Issuer<br/>그룹: coupon-issuer<br/>스레드: 3개]
    end

    subgraph "Processing"
        Redis[(Redis<br/>랭킹 업데이트)]
        Analytics[(Analytics<br/>데이터 분석)]
        MySQL[(MySQL<br/>쿠폰 발급)]
    end

    OrderService --> Topic1
    OutboxDispatcher --> Topic1
    CouponProducer --> Topic2

    Topic1 --> RankingConsumer
    Topic1 --> DataConsumer
    Topic2 --> CouponConsumer

    RankingConsumer --> Redis
    DataConsumer --> Analytics
    CouponConsumer --> MySQL
    CouponConsumer --> Topic3

    style Topic1 fill:#fff9e1
    style Topic2 fill:#e1f5ff
    style Topic3 fill:#f0e1ff
    style RankingConsumer fill:#e1ffe1
    style DataConsumer fill:#ffe1e1
    style CouponConsumer fill:#ffe1f5
```

### Kafka 설정 요약

**Producer 설정:**
- Acks: `all` (모든 복제본 확인)
- Idempotence: `enabled` (중복 방지)
- Compression: `snappy` (압축)
- Retries: `3`

**Consumer 설정:**
- Auto Offset Reset: `earliest`
- Enable Auto Commit: `false` (수동 ACK)
- Max Poll Records: `500`
- Concurrency: `3` (각 컨슈머 그룹당)

</details>

---

## 🚀 캐싱 전략

<details>
<summary><b>⚡ Redis 캐싱 전략 (Cache-Aside Pattern)</b></summary>

### 캐시 읽기 플로우

```mermaid
flowchart TD
    Start([API 요청]) --> CheckCache{Redis<br/>캐시 확인}

    CheckCache -->|캐시 히트| ReturnCache[캐시 데이터 반환]
    CheckCache -->|캐시 미스| QueryDB[MySQL DB 조회]

    QueryDB --> SaveCache[Redis에<br/>데이터 저장<br/>TTL 설정]
    SaveCache --> ReturnDB[DB 데이터 반환]

    ReturnCache --> End([응답])
    ReturnDB --> End

    style CheckCache fill:#fff9e1
    style ReturnCache fill:#e1ffe1
    style QueryDB fill:#ffe1e1
    style SaveCache fill:#e1f5ff
```

### 캐시 영역 및 TTL

| 캐시 영역 | 키 패턴 | TTL | 용도 |
|----------|---------|-----|------|
| `products` | `products:*` | 3분 | 상품 목록 |
| `product-detail` | `product-detail:{id}` | 10분 | 상품 상세 |
| `coupon-info` | `coupon-info:{id}` | 5분 | 쿠폰 정보 |
| `user-orders` | `user-orders:{userId}` | 10분 | 사용자 주문 목록 |
| `product:{id}:stock` | - | 30초 | 실시간 재고 |
| `ranking:*` | `ranking:{period}:{date}` | 1시간 | 상품 랭킹 (Sorted Set) |

### 분산 락 키

| 락 타입 | 키 패턴 | Wait Time | Lease Time |
|--------|---------|-----------|------------|
| 쿠폰 발급 | `coupon:{id}:lock` | 2초 | 5초 |
| 재고 예약 | `product:{id}:order:lock` | 2초 | 10초 |

</details>

---

## 🛠️ 기술 스택

### 백엔드 프레임워크
- **Spring Boot** 3.4.1
- **Spring Cloud** 2024.0.0
- **Java** 17

### 데이터 저장소
- **MySQL** 8.0 - 주문, 상품, 사용자 데이터
- **Redis** 7.0 - 캐싱, 분산 락, 랭킹

### 메시징
- **Apache Kafka** 7.6.0 - 이벤트 스트리밍
- 3-broker 클러스터
- 4개 토픽 (주문 이벤트, 쿠폰 발급 요청/결과)

### 주요 라이브러리
- **Spring Data JPA** - ORM
- **Redisson** - 분산 락
- **Lettuce** - Redis 클라이언트
- **Spring Kafka** - Kafka 통합
- **SpringDoc OpenAPI** - API 문서 (Swagger UI)
- **Testcontainers** - 통합 테스트
- **Lombok** - 보일러플레이트 코드 제거

---

## ✨ 주요 기능

### 1. 상품 카탈로그
- ✅ 커서 기반 페이징 (대용량 데이터 최적화)
- ✅ Redis 캐싱 (3분 TTL)
- ✅ 전체 텍스트 검색 및 카테고리 필터링
- ✅ 정렬 옵션 (ID, 이름, 가격 등)

### 2. 지갑 관리
- ✅ 멱등성 보장 (Idempotency-Key 헤더)
- ✅ 비관적 락(SELECT FOR UPDATE)을 통한 동시성 제어
- ✅ 거래 내역 추적
- ✅ 오버플로우 방지 (Math.addExact)
- ✅ 환불 메커니즘 (보상 트랜잭션)

### 3. 주문 처리
- ✅ 포괄적인 검증:
  - 재고 가용성 체크 (낙관적 락)
  - 쿠폰 적용 가능 여부 검증
  - 지갑 잔액 확인
  - 총액 매칭 (충돌 감지)
- ✅ 주문 완료 시 이벤트 발행
- ✅ 멱등성 보장 (request_key)
- ✅ 다중 상품 주문 지원
- ✅ 할인 계산 (비율/고정 금액)
- ✅ 쿠폰 사용 추적

### 4. 쿠폰 시스템
- ✅ 두 가지 발급 방식:
  - **동기**: 즉시 발급 (분산 락 사용)
  - **비동기**: Kafka 기반 (Lua 스크립트 검증)
- ✅ 기능:
  - 비율 할인 및 고정 금액 할인
  - 최소 구매 금액 조건
  - 최대 할인 금액 제한
  - 유효 기간 (시작일/종료일)
  - 발급 한도 (선착순)
  - Redis 원자적 카운터로 동시성 제어
  - 쿠폰 해제 (보상 처리)

### 5. 재고 관리
- ✅ 이중 저장소:
  - MySQL (영구 저장)
  - Redis (빠른 조회)
- ✅ 낙관적 락 (version 필드)으로 DB 충돌 방지
- ✅ 재시도 메커니즘 (3회, 지수 백오프)
- ✅ 재고 변동 감사 추적
- ✅ 상품별 분산 락으로 안전한 예약
- ✅ Cache-Aside 패턴으로 빠른 읽기
- ✅ 주문 취소 시 재고 복원

### 6. 상품 랭킹 시스템
- ✅ 다중 랭킹 기간:
  - **REALTIME** - 주문 완료 시 즉시 업데이트
  - **DAILY** - 일별 집계 랭킹
  - **WEEKLY** - 주간 집계 랭킹
- ✅ Redis Sorted Set으로 O(log n) 성능
- ✅ Spring Application Events + Kafka로 업데이트
- ✅ 점수 기준:
  - 주문 수량
  - 판매 금액
  - 고객 참여 지표
- ✅ 과거 날짜 조회 지원
- ✅ Graceful degradation (Redis 장애 시 빈 결과)

---

## 🔒 동시성 제어 전략

| 기능 | 제어 방식 | 설명 |
|------|----------|------|
| **지갑 충전/차감** | 비관적 락 | `SELECT ... FOR UPDATE`로 트랜잭션 격리 |
| **재고 예약** | 분산 락 (Redisson) | `product:{id}:order:lock`으로 상품별 직렬화 |
| **쿠폰 발급** | 분산 락 + Redis INCR | `coupon:{id}:lock` + 원자적 카운터 |
| **재고 업데이트** | 낙관적 락 | `version` 필드로 충돌 감지 및 재시도 |
| **멱등성 보장** | DB Unique 제약 | `(user_id, idempotency_key)` 복합 유니크 키 |

### Deadlock 방지
- 정렬된 순서로 락 획득 (product_id 오름차순)
- Timeout 설정 (2초 wait, 5-10초 lease)
- 실패 시 빠른 실패 전략 (Fail-Fast)

---

## 🏛️ 아키텍처 패턴

### 1. 헥사고날 아키텍처 (Ports & Adapters)
- **Inbound Ports**: 유즈케이스 인터페이스 (`application.port.in`)
- **Outbound Ports**: 도메인 포트 인터페이스 (`domain.port.out`)
- **Adapters**: JPA 리포지토리가 Outbound Ports 구현

### 2. CQRS (Command Query Responsibility Segregation)
- 읽기 작업 분리 (ProductReadPort, ProductDetailReadPort)
- 쓰기 작업 분리 (OrderWritePort, WalletReadWritePort)

### 3. 이벤트 기반 아키텍처
- 도메인 이벤트를 서비스에서 발행
- 다중 이벤트 리스너가 독립적으로 처리
- Outbox 패턴으로 보장된 전달
- Kafka로 분산 이벤트 스트리밍

### 4. Saga 패턴 (보상 트랜잭션)
- 주문 생성 실패 → 지갑 환불
- 쿠폰 발급 실패 → 발급 내역 해제
- 재고 예약 실패 → 재고 복원

### 5. 멱등성 패턴
- `Idempotency-Key` 헤더로 API 요청 식별
- DB Unique 제약 `(user_id, idempotency_key)`
- 재시도 시 기존 결과 반환

### 6. Cache-Aside 패턴
- Redis 캐시 먼저 확인
- 미스 시 DB 조회
- TTL 기반 캐시 만료
- 상품, 쿠폰, 재고에 적용

### 7. Outbox Event Dispatcher 패턴
- 이벤트를 DB에 트랜잭션 내 저장
- 스케줄러가 5초마다 폴링
- 배치 처리 (20개씩)
- 지수 백오프 재시도 (30초 * retry_count)
- 최종 상태: PENDING, SENT, FAILED

---

## 💻 로컬 개발 환경 설정

### 1. 인프라 실행

Docker Compose로 MySQL, Redis, Kafka를 실행합니다.

```bash
docker-compose up -d
```

**실행되는 서비스:**
- MySQL 8.0 (포트: 3306)
- Redis 7.0 (포트: 6379)
- Apache Kafka 3-broker 클러스터 (포트: 9092, 9093, 9094)
- Zookeeper (포트: 2181)

### 2. 애플리케이션 실행

```bash
./gradlew bootRun
```

- `local` 프로필로 실행 (기본값)
- 자동 스키마 생성 및 데이터 시드 (`schema.sql`, `data.sql`)
- 애플리케이션 포트: `8080`

### 3. Swagger UI 접속

```
http://localhost:8080/swagger-ui.html
```

- `local` 프로필에서만 활성화
- 모든 API 엔드포인트 테스트 가능

### 4. 테스트 실행

```bash
./gradlew test
```

- Testcontainers로 격리된 MySQL 컨테이너 실행
- 통합 테스트 자동 실행
- `UserFlowIntegrationTest`로 전체 사용자 플로우 검증

---

## 📂 프로젝트 구조

```
src/
├── main
│   ├── java/kr/hhplus/be/server
│   │   ├── interfaces/web           # REST Controllers
│   │   ├── application/service      # Application Services (유즈케이스)
│   │   ├── domain/model             # Domain Models (불변 Records)
│   │   └── infrastructure           # Persistence, Cache, Kafka Adapters
│   │       ├── persistence/adapter  # JPA Adapters
│   │       ├── kafka/               # Kafka Producers/Consumers
│   │       ├── lock/                # Distributed Lock
│   │       ├── outbox/              # Outbox Event Dispatcher
│   │       └── config/              # Configuration Classes
│   └── resources
│       ├── application.yml          # Spring 설정
│       ├── schema.sql               # DDL (테이블 생성)
│       └── data.sql                 # 시드 데이터
└── test
    └── java/kr/hhplus/be/server
        └── interfaces/web           # 통합 테스트 (Testcontainers)
```

---

## 📚 추가 문서

- [API 명세서](./docs) - 상세 API 계약서
- [ERD](./docs/ERD.md) - 엔티티 관계 다이어그램
- [인프라 구성도](./docs/인프라_구성도.md) - 인프라 토폴로지 및 컴포넌트 책임
- [동시성 제어 설계](./docs/claude-code/concurrency-control-design.md) - 락 전략, Redis 사용법, 테스트 계획

---

## 🚀 빠른 시작

```bash
# 1. 리포지토리 클론 및 Java 17 설치
git clone <repository-url>
cd ecommerce-platform

# 2. 인프라 실행 (MySQL, Redis, Kafka)
docker-compose up -d

# 3. 애플리케이션 실행
./gradlew bootRun

# 4. Swagger UI에서 API 테스트
open http://localhost:8080/swagger-ui.html

# 5. 테스트 실행
./gradlew test
```

---

## 📞 문의 및 기여

이슈나 개선 제안은 GitHub Issues를 통해 등록해주세요.

---

**Built with ❤️ using Spring Boot 3, Kafka, Redis, and MySQL**
