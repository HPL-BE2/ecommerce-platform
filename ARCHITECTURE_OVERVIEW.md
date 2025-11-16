# E-Commerce Platform - Architecture & Order Domain Overview

**Project:** Spring Boot 3 Hexagonal Architecture E-Commerce Platform
**Java Version:** 17
**Total Java Source Files:** 96
**Architecture Pattern:** Hexagonal (Ports & Adapters) with Clean Architecture

---

## 1. PROJECT STRUCTURE & PACKAGES

### Layer Organization
```
kr.hhplus.be.server/
├── interfaces/web/           # REST Controllers (Input Adapters)
├── application/
│   ├── service/              # Application/Use Case Layer
│   └── port/
│       └── in/               # Input Ports (Use Case Interfaces)
├── domain/
│   ├── model/                # Core Domain Models (Immutable Records)
│   └── port/
│       └── out/              # Output Ports (Infrastructure Contracts)
└── infrastructure/
    ├── persistence/
    │   ├── adapter/          # Output Adapters (JPA, DB)
    │   ├── entity/           # JPA Entities
    │   └── repo/             # Spring Data JPA Repositories
    ├── lock/                 # Redis Distributed Lock
    ├── outbox/               # Outbox Event Pattern
    ├── coupon/               # Coupon-specific Infrastructure
    ├── ranking/              # Product Ranking Cache
    └── config/               # Spring Configuration
```

### Key Characteristics
- **Isolation:** Domain layer has zero Spring dependencies
- **Contracts:** Interfaces define boundaries between layers
- **Adapter Pattern:** Infrastructure adapters implement ports
- **Clean Dependencies:** Flow from outer (infrastructure) → inner (domain)

---

## 2. ORDER DOMAIN IMPLEMENTATION

### 2.1 Domain Models

#### OrderModels (Core Value Objects)
Location: `/domain/model/OrderModels.java`

```java
public record OrderItem(Long productId, String name, int unitPrice, int qty, int lineTotal)
public record OrderSummary(Long orderId, Long userId, String status, int subtotal, 
                           int discount, int total, String requestKey, 
                           OffsetDateTime completedAt, List<OrderItem> items)
public record ProductPrice(Long productId, String name, int unitPrice)
public record CouponInfo(Long couponId, String code, String type, int value,
                         Long issuanceId, Integer minAmount, Integer maxDiscount)
```

**Key Characteristics:**
- Immutable records (Java 14+)
- Contain calculation context (price, discount, qty)
- Support DTOs for multiple use cases
- No persistence concerns

#### OrderEntity (JPA Persistence)
Location: `/infrastructure/persistence/entity/OrderEntity.java`

```
Table: orders
├── id (PK, auto-increment)
├── user_id (FK → users, indexed)
├── order_no (unique, 32 chars)
├── request_key (unique per user, idempotency)
├── status (RESERVED, COMPLETED, CANCELED, FAILED)
├── discount (BIGINT)
├── total (BIGINT)
├── coupon_issuance_id (FK → coupon_issuances)
├── created_at / updated_at (timestamps)
└── Index: (user_id, request_key) - UNIQUE
```

#### OrderItemEntity (Line Items)
Location: `/infrastructure/persistence/entity/OrderItemEntity.java`

```
Table: order_items
├── id (PK, auto-increment)
├── order_id (FK → orders)
├── product_id (FK → products)
├── name, qty, unit_price, line_total
```

### 2.2 Order Service - Application Layer
Location: `/application/service/OrderService.java`

**Implements Two Use Cases:**
- `CreateOrderUseCase` - Order reservation (RESERVED state)
- `CompleteOrderUseCase` - Order completion + event publishing

#### Order Creation Flow (CreateOrderUseCase.create)

1. **Idempotency Check** (Pessimistic)
   - Check request_key uniqueness per user
   - Return existing order if found
   - Ensures exactly-once semantics

2. **Validation Phase**
   - Product existence & pricing
   - Inventory availability
   - Coupon validity

3. **Price Calculation**
   - Subtotal: sum(product_price × qty)
   - Discount: coupon application (PERCENT or FIXED)
   - Total: subtotal - discount
   - Validate against client-provided expectedTotal

4. **Inventory Reservation** (With Concurrency Control)
   ```java
   // Deadlock Prevention: Sort by productId before locking
   items.stream()
       .sorted((a, b) -> Long.compare(a.productId(), b.productId()))
       .forEach(item -> inventoryService.reserveWithLock(item.productId(), qty, null))
   ```
   - Uses distributed locks (Redis/Redisson)
   - Optimistic lock retry on failure
   - Tracks stock movements in stock_movements table

5. **Payment Processing**
   ```java
   walletService.debit(new CreateWalletDebitUseCase.Command(
       userId, total, "ORDER:" + requestKey, "ORDER", requestKey
   ))
   ```
   - Pessimistic lock on wallet row
   - Idempotent debit via idempotency key
   - Records wallet transaction

6. **Order Persistence**
   - Creates order in RESERVED state
   - Updates stock_movements with actual orderId (from temp reference)
   - Returns orderId + payment breakdown

#### Order Completion Flow (CompleteOrderUseCase.complete)

1. **State Transition**
   - Marks order as COMPLETED
   - Updates timestamp

2. **Event Publishing** (Outbox Pattern)
   ```java
   OrderCompletedEvent event = new OrderCompletedEvent(
       orderId, userId, subtotal, discount, total, requestKey, 
       completedAt, items
   )
   orderEventPublisher.publish(event)  // → OutboxOrderEventPublisher
   ```

3. **Returns Result**
   - OrderId + Total Amount

### 2.3 Output Ports (Infrastructure Contracts)

#### OrderWritePort
```java
Optional<Long> findOrderIdByRequestKey(Long userId, String requestKey)
Long createReservedOrder(Long userId, List<OrderItem> items, int subtotal, 
                         int discount, int total, String requestKey, Long couponIssuanceId)
OrderSummary markOrderCompleted(Long orderId)
```

#### OrderEventPublisher
```java
void publish(OrderCompletedEvent event)
```

### 2.4 Persistence Adapter
Location: `/infrastructure/persistence/adapter/OrderPersistenceAdapter.java`

**Implements:** ProductPricePort, InventoryReservePort, CouponValidatePort, OrderWritePort

**Key Methods:**

1. **Idempotent Order Creation**
   ```java
   // ThreadLocal for temp stock movement reference tracking
   TEMP_REF_ID.set("TEMP:" + UUID.randomUUID())
   
   // Create order entity, save items
   // Update stock_movements with orderId (from TEMP_REF_ID)
   ```

2. **Inventory Reservation with Retry**
   ```java
   @Retryable(
       retryFor = {ObjectOptimisticLockingFailureException.class},
       maxAttempts = 3,
       backoff = @Backoff(delay = 100, multiplier = 2)
   )
   void reserve(Long productId, int qty, Long orderId)
   ```
   - Optimistic lock (version field)
   - Decreases inventory
   - Records stock movement

3. **Stock Movement Tracking**
   ```
   Table: stock_movements
   ├── product_id, qty, reason (RESERVE, RESTOCK, CANCEL)
   └── ref_id (tracks order or temp id)
   ```

---

## 3. EXISTING TRANSACTION HANDLING PATTERNS

### 3.1 Idempotency Pattern

**Wallet Transactions:**
```
Table: wallet_transactions
├── UNIQUE KEY (user_id, idempotency_key)
├── Ensures exactly-once payment processing
└── Checked before processing: findTxByIdempotency()
```

**Order Requests:**
```
Table: orders
├── UNIQUE KEY (user_id, request_key)
├── Via Idempotency-Key HTTP header
└── Returns existing order on duplicate request
```

**Implementation in WalletService:**
```java
@Transactional
Result debit(Command cmd) {
    // 1) Check idempotency first (before locking)
    var existing = rwPort.findTxByIdempotency(userId, idempotencyKey)
    if (existing.isPresent()) return existing  // Idempotent response
    
    // 2) Pessimistic lock on wallet
    Wallet wallet = rwPort.lockByUserId(userId)
    
    // 3) Balance check & debit
    // 4) Record transaction
    // 5) Update balance
}
```

### 3.2 Concurrency Control Mechanisms

#### Pessimistic Locking (for Wallets)
```java
// JPA Repository with @Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<WalletEntity> lockByUserId(Long userId)
```
- Acquires database-level row lock
- Prevents simultaneous wallet modifications
- Used for critical financial operations

#### Optimistic Locking (for Inventory)
```java
@Entity
@Getter @Setter
class InventoryEntity {
    @Version  // Auto-incremented on update
    private Long version;
    
    void decreaseStock(int qty) {
        if (stock < qty) throw new IllegalStateException(...)
        stock -= qty
    }
}
```
- Updates fail if version changed between read/write
- Automatic retry in OrderPersistenceAdapter
- Better throughput for high-concurrency inventory

#### Distributed Locks (for Shared Resources)
```java
@DistributedLock(
    key = "'product:' + #productId + ':order:lock'",
    leaseTime = 10000,  // 10 seconds
    waitTime = 2000     // 2 second timeout
)
void reserveWithLock(Long productId, int qty, Long orderId)
```
- Redis/Redisson-based locking
- Cross-process synchronization
- Prevents overselling in distributed systems

### 3.3 Transaction Boundaries

**Wallet Service:**
```java
@Service
@Transactional  // Service-level transaction management
public class WalletService {
    // All methods wrapped in @Transactional
    // Automatic rollback on exception
}
```

**Order Service:**
```java
@Service
@Transactional
public class OrderService {
    // create(): 
    //   - Find prices
    //   - Reserve inventory (within transaction)
    //   - Debit wallet (calls WalletService.debit())
    //   - Create order (all within single DB transaction)
    // 
    // Rollback scenario:
    //   - If inventory insufficient → rollback all
    //   - If wallet insufficient → rollback all
    //   - Payment idempotent key prevents duplicate debits
}
```

**Nested Transactions:**
- OrderService.create() calls WalletService.debit()
- Both @Transactional - Spring uses propagation=REQUIRED
- Single logical transaction to database
- Maintains ACID across multiple service boundaries

### 3.4 Transaction Failure Handling

```
Order Creation Flow:
┌─────────────────────────────────────────┐
│ Transaction Boundary (Spring)           │
│                                          │
│ 1. Validate & Calculate               │
│ 2. Reserve Inventory (Optimistic Lock) │
│ 3. Debit Wallet (Pessimistic Lock)   │
│ 4. Create Order Entity                │
│ 5. Update Stock Movements             │
└─────────────────────────────────────────┘
     │
     └─→ Any Exception → ROLLBACK all
         (inventory, wallet, order)

Exception Handling:
- ObjectOptimisticLockingFailureException
  → Auto-retry 3 times with exponential backoff
  
- Inventory insufficient
  → IllegalStateException → Rollback
  
- Wallet insufficient
  → IllegalStateException → Rollback
  
- Coupon invalid
  → IllegalArgumentException → Rollback
```

---

## 4. SERVICE DEPENDENCIES & INTERACTIONS

### 4.1 Dependency Graph

```
┌─────────────────────────────────────────────────────────┐
│ Web Layer (REST Controllers)                             │
│  └─ OrderController                                     │
│  └─ WalletController                                    │
│  └─ CouponController                                    │
└────────────┬────────────────────────────────────────────┘
             │
┌────────────▼────────────────────────────────────────────┐
│ Application Services (Use Cases)                         │
│                                                          │
│  ┌──────────────────────────────────────────────────┐  │
│  │ OrderService                                     │  │
│  │ ├─ depends: WalletService                       │  │
│  │ ├─ depends: InventoryService                    │  │
│  │ ├─ depends: ProductPricePort                    │  │
│  │ ├─ depends: CouponValidatePort                  │  │
│  │ └─ depends: OrderEventPublisher                 │  │
│  └──────────────────────────────────────────────────┘  │
│                                                          │
│  ┌──────────────────────────────────────────────────┐  │
│  │ WalletService                                    │  │
│  │ └─ depends: WalletReadWritePort                  │  │
│  └──────────────────────────────────────────────────┘  │
│                                                          │
│  ┌──────────────────────────────────────────────────┐  │
│  │ InventoryService                                │  │
│  │ ├─ depends: InventoryReservePort                │  │
│  │ └─ depends: RedisTemplate                       │  │
│  └──────────────────────────────────────────────────┘  │
│                                                          │
│  ┌──────────────────────────────────────────────────┐  │
│  │ CouponService                                    │  │
│  │ ├─ depends: CouponReadWritePort                  │  │
│  │ └─ depends: RedisTemplate (counter)             │  │
│  └──────────────────────────────────────────────────┘  │
└────────────┬────────────────────────────────────────────┘
             │
┌────────────▼────────────────────────────────────────────┐
│ Domain Ports (Infrastructure Contracts)                  │
│                                                          │
│  OrderWritePort              ┐                          │
│  OrderEventPublisher         ├─ Domain Ports (out)      │
│  WalletReadWritePort         │                          │
│  InventoryReservePort        │                          │
│  CouponReadWritePort         ┘                          │
└────────────┬────────────────────────────────────────────┘
             │
┌────────────▼────────────────────────────────────────────┐
│ Infrastructure Adapters & Persistence                    │
│                                                          │
│  ┌──────────────────────────────────────────────────┐  │
│  │ OrderPersistenceAdapter                          │  │
│  │ (implements 4 ports)                             │  │
│  │ ├─ SpringOrderJpa                               │  │
│  │ ├─ SpringOrderItemJpa                           │  │
│  │ ├─ SpringInventoryJpa                           │  │
│  │ ├─ SpringStockMovementJpa                       │  │
│  │ └─ SpringCouponJpa                              │  │
│  └──────────────────────────────────────────────────┘  │
│                                                          │
│  ┌──────────────────────────────────────────────────┐  │
│  │ WalletPersistenceAdapter                         │  │
│  │ ├─ SpringWalletJpa                              │  │
│  │ └─ SpringWalletTxJpa                            │  │
│  └──────────────────────────────────────────────────┘  │
│                                                          │
│  ┌──────────────────────────────────────────────────┐  │
│  │ OutboxOrderEventPublisher                        │  │
│  │ └─ SpringOutboxEventJpa                          │  │
│  └──────────────────────────────────────────────────┘  │
└────────────┬────────────────────────────────────────────┘
             │
┌────────────▼────────────────────────────────────────────┐
│ External Infrastructure                                  │
│                                                          │
│  MySQL Database (InnoDB)                               │
│  │  ├─ orders, order_items                            │
│  │  ├─ wallets, wallet_transactions                   │
│  │  ├─ inventory, stock_movements                      │
│  │  ├─ coupons, coupon_issuances                       │
│  │  └─ outbox_events                                   │
│  │                                                     │
│  Redis (Redisson)                                      │
│  │  ├─ Distributed Locks (product:X:order:lock)       │
│  │  ├─ Stock Cache (product:X:stock)                  │
│  │  └─ Coupon Counters (coupon:X:count)               │
│  │                                                     │
│  Message Queue (Optional - MockMessageProducer)        │
│  └─ Receives OrderCompletedEvent from Outbox          │
└────────────────────────────────────────────────────────┘
```

### 4.2 Service Interaction Example: Order Creation

```
HTTP POST /api/v1/orders + "Idempotency-Key: xyz"
    ↓
OrderController.create(OrderDtos.CreateOrderRequest)
    ↓
OrderService.create(CreateOrderUseCase.Command)
    │
    ├─→ OrderWritePort.findOrderIdByRequestKey()
    │       ├─→ OrderPersistenceAdapter
    │       └─→ SpringOrderJpa.findByUserIdAndRequestKey()
    │
    ├─→ ProductPricePort.loadPrices(productIds)
    │       ├─→ OrderPersistenceAdapter (multi-implement)
    │       └─→ SpringProductPriceJpa.findPrice()
    │
    ├─→ InventoryReservePort.lockInventories(productIds)
    │       ├─→ OrderPersistenceAdapter
    │       └─→ SpringInventoryJpa.lockByProductIds()
    │
    ├─→ CouponValidatePort.findApplicable(userId, code)
    │       ├─→ OrderPersistenceAdapter
    │       ├─→ SpringCouponJpa.findByCode()
    │       └─→ SpringCouponIssuanceJpa.findByCouponIdAndUserId()
    │
    ├─→ InventoryService.reserveWithLock(productId, qty, null)
    │   @DistributedLock(key = "'product:'+productId+':order:lock'")
    │       │
    │       ├─→ Check Redis cache (product:X:stock)
    │       │
    │       └─→ InventoryReservePort.reserve(productId, qty, null)
    │           ├─→ OrderPersistenceAdapter
    │           ├─→ SpringInventoryJpa.findById()
    │           │   (with Optimistic Lock @Version)
    │           │
    │           ├─→ InventoryEntity.decreaseStock(qty)
    │           │   └─→ Throws if insufficient
    │           │
    │           └─→ SpringStockMovementJpa.save()
    │               (TEMP reference initially)
    │
    ├─→ WalletService.debit(CreateWalletDebitUseCase.Command)
    │   @Transactional
    │       │
    │       ├─→ WalletReadWritePort.findTxByIdempotency()
    │       │   └─→ Check idempotency key (duplicate prevention)
    │       │
    │       ├─→ WalletReadWritePort.lockByUserId(userId)
    │       │   @Lock(LockModeType.PESSIMISTIC_WRITE)
    │       │   └─→ SpringWalletJpa (Pessimistic Lock)
    │       │
    │       ├─→ Check balance >= amount
    │       │
    │       ├─→ WalletReadWritePort.saveDebitTx()
    │       │   └─→ SpringWalletTxJpa.save()
    │       │
    │       └─→ WalletReadWritePort.updateBalance()
    │           └─→ SpringWalletJpa.save()
    │
    └─→ OrderWritePort.createReservedOrder(...)
        ├─→ Create OrderEntity (status=RESERVED)
        ├─→ Create OrderItemEntity entries
        ├─→ Update stock_movements with orderId
        └─→ Return orderId

    ↓
OrderController returns ApiEnvelope<CreateOrderResponse>
```

### 4.3 Service Responsibility Matrix

| Service | Responsibility | Dependencies | Transactions |
|---------|---|---|---|
| **OrderService** | Orchestrate order flow, validate, coordinate sub-services | ProductPrice, Inventory, Coupon, Wallet, EventPublisher | Create, Complete |
| **WalletService** | Manage user balance & transactions | WalletReadWritePort | Topup, Debit |
| **InventoryService** | Manage stock with caching & locking | InventoryReservePort, RedisTemplate | Reserve, Restore |
| **CouponService** | Issue & validate coupons | CouponReadWritePort, RedisTemplate (counter) | Issue |
| **ProductService** | Manage catalog & pricing | ProductReadPort | List, GetDetail |

---

## 5. EXISTING EVENT HANDLING MECHANISMS

### 5.1 Outbox Pattern (Transactional Outbox)

**Purpose:** Guarantee event delivery reliability in distributed systems

**Implementation:**

#### 1. Event Publishing (Synchronous, within Transaction)

```
When: OrderService.complete() called
└─→ OrderEventPublisher.publish(OrderCompletedEvent)
    └─→ OutboxOrderEventPublisher.publish(event)
        ├─→ Serialize event to JSON
        └─→ Insert into outbox_events table (within DB transaction)
            └─→ Table: outbox_events
                ├── id (PK)
                ├── aggregate_type = "order"
                ├── aggregate_id = orderId
                ├── event_type = "ORDER_COMPLETED"
                ├── payload = JSON (serialized event)
                ├── status = PENDING
                ├── retry_count = 0
                ├── next_retry_at = now()
                └── created_at, updated_at
```

**Key Benefits:**
- Event insertion SAME transaction as order COMPLETED
- If order update succeeds, event record created
- If both fail together, no orphaned events
- Exactly-once delivery guarantee

#### 2. Event Dispatch (Asynchronous, Scheduled)

```
OutboxEventDispatcher (@Scheduled, 5-second intervals)
    │
    ├─→ Query outbox_events WHERE status = PENDING AND next_retry_at <= now()
    │   (Index: idx_outbox_status_next)
    │
    ├─→ For each event:
    │   ├─→ OutboundMessageProducer.send(eventType, payload)
    │   │   └─→ MockMessageProducer (current implementation)
    │   │       → Could be Kafka, RabbitMQ, HTTP, etc.
    │   │
    │   ├─→ On success:
    │   │   ├─→ Update status = SENT
    │   │   ├─→ Set sent_at = now()
    │   │   └─→ Clear last_error
    │   │
    │   └─→ On failure:
    │       ├─→ Increment retry_count
    │       ├─→ Record last_error
    │       ├─→ Calculate next_retry_at = now() + delay * retry_count
    │       ├─→ Status remains PENDING (or FAILED if max retries exceeded)
    │       └─→ Automatic exponential backoff (30s, 60s, 120s, ...)
    │
    └─→ @Transactional – each dispatch attempt is atomic

Retry Strategy:
- Max retries: 5 (configurable: outbox.dispatcher.max-retry)
- Delay: 30 seconds × retry_count (configurable: outbox.dispatcher.retry-delay-seconds)
- Status: PENDING → FAILED (after max retries)
```

#### 3. Event Model

```java
public record OrderCompletedEvent(
    Long orderId,
    Long userId,
    int subtotal,
    int discount,
    int total,
    String requestKey,
    OffsetDateTime completedAt,
    List<Item> items
) {
    public record Item(
        Long productId,
        String name,
        int unitPrice,
        int qty,
        int lineTotal
    ) {}
}
```

Serialized to JSON in outbox_events.payload

#### 4. Configuration (OutboxConfig)

```java
@Configuration
@EnableScheduling      // Enables @Scheduled methods
@EnableAsync          // Enables @Async methods
public class OutboxConfig {}
```

**Configurable Properties:**
```yaml
outbox:
  dispatcher:
    enabled: true                    # Enable/disable dispatcher
    batch-size: 20                  # Events per dispatch run
    max-retry: 5                    # Maximum retry attempts
    retry-delay-seconds: 30         # Base retry delay
    interval-ms: 5000               # Dispatch check interval
```

### 5.2 Event Flow Diagram

```
Order Flow:
┌────────────────────────────────────────────────┐
│ 1. Complete Order (within @Transactional)      │
│                                                 │
│ OrderService.complete(orderId)                 │
│   ├─ orderWritePort.markOrderCompleted(...)   │
│   │  └─ OrderEntity.status = COMPLETED        │
│   │                                             │
│   └─ orderEventPublisher.publish(event)       │
│      └─ OutboxOrderEventPublisher             │
│         └─ OutboxEventEntity.save(...)        │
│            └─ INSERT into outbox_events       │
│               (status=PENDING)                │
│                                                 │
│ COMMIT – both changes persisted atomically    │
└────────────────────────────────────────────────┘
                     ↓ (DB transaction committed)
┌────────────────────────────────────────────────┐
│ 2. Outbox Dispatcher (separate scheduled task) │
│                                                 │
│ Every 5 seconds: @Scheduled(fixedDelay=5000)  │
│   ├─ SELECT * FROM outbox_events              │
│   │  WHERE status='PENDING' AND next_retry <= now()
│   │                                             │
│   ├─ For each row:                            │
│   │  ├─ OutboundMessageProducer.send(...)     │
│   │  │                                         │
│   │  └─ If success:                           │
│   │     └─ UPDATE status='SENT'               │
│   │                                             │
│   └─ @Transactional per batch                 │
└────────────────────────────────────────────────┘
                     ↓
┌────────────────────────────────────────────────┐
│ 3. External System (Message Queue)             │
│                                                 │
│ MockMessageProducer.send(type, payload)       │
│  └─ Currently logs; could be:                 │
│     - Kafka topic                             │
│     - RabbitMQ exchange                       │
│     - HTTP webhook                            │
│     - Event stream                            │
└────────────────────────────────────────────────┘
```

### 5.3 Why Outbox Pattern?

**Problem in naive approach:**
```
Without Outbox:
┌─────────────────┐
│ Update Order    │
│ (COMMITTED)     │
└────────┬────────┘
         │
         ├─→ Network call to message broker
         │   (possible failure!)
         │
         └─→ Message not sent, order updated
             (data inconsistency)
```

**Solution with Outbox:**
```
With Outbox:
┌──────────────────────────────────────────┐
│ Single DB Transaction:                    │
│ - Update order status = COMPLETED        │
│ - Insert outbox event (status=PENDING)   │
│ (COMMITTED atomically)                    │
└──────────────────────────────────────────┘
         │
         ├─→ Order update GUARANTEED
         │
         └─→ Event record GUARANTEED
             (even if network fails next)

Separate Dispatcher (no impact on order):
         ├─→ Retry logic built-in
         ├─→ Exponential backoff
         ├─→ Manual intervention if needed
         └─→ Eventually consistent
```

### 5.4 Current Event Types

**OrderCompletedEvent**
- Triggered: When order marked COMPLETED
- Data: Order details, items, timestamps
- Producer: OutboxOrderEventPublisher
- Consumer: (TBD - MockMessageProducer currently)

**Potential Future Events:**
- OrderCreatedEvent (on RESERVED state)
- OrderCancelledEvent (refund scenario)
- PaymentCompletedEvent (separate flow)
- InventoryReservedEvent (tracking)

### 5.5 Outbox Status Lifecycle

```
PENDING ─────────→ SENT (success)
  ↑
  └─ (retry_count < max_retry) ← PENDING (after backoff)
  
PENDING ─────────→ FAILED (after max retries)
  
Status Values:
- PENDING: Waiting to be sent or retried
- SENT: Successfully delivered
- FAILED: Max retries exhausted, manual intervention needed
```

---

## Summary

### Core Architecture Principles
1. **Hexagonal Design** – Domain insulated from infrastructure
2. **Port-Adapter Pattern** – Contracts between layers
3. **Transactional Consistency** – ACID-compliant order operations
4. **Idempotency** – Request keys prevent duplicate processing
5. **Concurrency Control** – Multi-level locking (pessimistic, optimistic, distributed)
6. **Reliable Messaging** – Outbox pattern for event publishing
7. **Cache Integration** – Redis for inventory & coupon scalability

### Key Technologies
- **Framework:** Spring Boot 3
- **Database:** MySQL 8 (InnoDB)
- **Caching:** Redis 7 (Redisson client)
- **ORM:** Spring Data JPA
- **Locking:** Redisson distributed locks
- **Event Publishing:** Transactional Outbox
- **Async:** Spring Scheduling, @Scheduled

### Order Domain Scope
✓ Order creation (with inventory, wallet, coupon validation)
✓ Order completion (with event publishing)
✓ Idempotency (request-key based)
✓ Concurrency control (inventory, wallet)
✓ Event publishing (outbox pattern)

