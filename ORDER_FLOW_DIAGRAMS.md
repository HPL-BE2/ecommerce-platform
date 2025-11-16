# Order Domain - Flow Diagrams & Sequences

## Order Creation Flow - Detailed Sequence

```
┌─────────────┐
│   Client    │
└──────┬──────┘
       │ POST /api/v1/orders + Idempotency-Key
       ▼
┌──────────────────────────────────────────────────────────────┐
│  OrderController                                             │
│  .create(OrderDtos.CreateOrderRequest)                      │
└──────────┬───────────────────────────────────────────────────┘
           │
           ▼
┌──────────────────────────────────────────────────────────────┐
│  ┌─ OrderService.create(CreateOrderUseCase.Command) ────┐   │
│  │                                                        │   │
│  │ @Service                                              │   │
│  │ @Transactional                                        │   │
│  │                                                        │   │
│  │ [Step 1] Idempotency Check                           │   │
│  │ ┌─────────────────────────────────────────────────┐ │   │
│  │ │ orderWritePort.findOrderIdByRequestKey()       │ │   │
│  │ │ ↓                                                │ │   │
│  │ │ OrderPersistenceAdapter                         │ │   │
│  │ │ ↓                                                │ │   │
│  │ │ SpringOrderJpa.findByUserIdAndRequestKey()     │ │   │
│  │ │ ↓                                                │ │   │
│  │ │ If found: return existing orderId (idempotent) │ │   │
│  │ └─────────────────────────────────────────────────┘ │   │
│  │                                                        │   │
│  │ [Step 2] Validate & Calculate                         │   │
│  │ ┌─────────────────────────────────────────────────┐ │   │
│  │ │ ✓ userId & items not empty                     │ │   │
│  │ │ ✓ requestKey not blank (idempotency)           │ │   │
│  │ │ ✓ qty > 0 for all items                        │ │   │
│  │ └─────────────────────────────────────────────────┘ │   │
│  │                                                        │   │
│  │ [Step 3] Load Product Prices                          │   │
│  │ ┌─────────────────────────────────────────────────┐ │   │
│  │ │ productPricePort.loadPrices(productIds)        │ │   │
│  │ │ ↓                                                │ │   │
│  │ │ OrderPersistenceAdapter (multi-impl)           │ │   │
│  │ │ ↓                                                │ │   │
│  │ │ SpringProductPriceJpa.findPrice()              │ │   │
│  │ │ ↓                                                │ │   │
│  │ │ Returns: {productId, name, unitPrice}          │ │   │
│  │ └─────────────────────────────────────────────────┘ │   │
│  │                                                        │   │
│  │ [Step 4] Lock & Check Inventory                       │   │
│  │ ┌─────────────────────────────────────────────────┐ │   │
│  │ │ invPort.lockInventories(productIds)            │ │   │
│  │ │ ↓                                                │ │   │
│  │ │ OrderPersistenceAdapter                         │ │   │
│  │ │ ↓                                                │ │   │
│  │ │ SpringInventoryJpa.lockByProductIds()          │ │   │
│  │ │ ↓ (SELECT ... FOR UPDATE - Pessimistic)        │ │   │
│  │ │ Returns: {productId, stock}                    │ │   │
│  │ └─────────────────────────────────────────────────┘ │   │
│  │                                                        │   │
│  │ [Step 5] Validate Coupon (if provided)                │   │
│  │ ┌─────────────────────────────────────────────────┐ │   │
│  │ │ couponPort.findApplicable(userId, code, sub)   │ │   │
│  │ │ ↓                                                │ │   │
│  │ │ OrderPersistenceAdapter                         │ │   │
│  │ │ ↓                                                │ │   │
│  │ │ 1. SpringCouponJpa.findByCode()                 │ │   │
│  │ │ 2. Check coupon date validity (startsAt/endsAt)│ │   │
│  │ │ 3. SpringCouponIssuanceJpa.findByCoupon...()   │ │   │
│  │ │ ↓                                                │ │   │
│  │ │ Returns: {couponId, type, value, minAmount...} │ │   │
│  │ │ Calculate discount (PERCENT or FIXED)          │ │   │
│  │ └─────────────────────────────────────────────────┘ │   │
│  │                                                        │   │
│  │ [Step 6] Calculate Totals                             │   │
│  │ ┌─────────────────────────────────────────────────┐ │   │
│  │ │ for each item:                                  │ │   │
│  │ │   lineTotal = unitPrice * qty                  │ │   │
│  │ │   subtotal += lineTotal                        │ │   │
│  │ │                                                 │ │   │
│  │ │ discount = coupon.calculate(subtotal)          │ │   │
│  │ │ total = subtotal - discount                    │ │   │
│  │ │                                                 │ │   │
│  │ │ if (expectedTotal != total)                    │ │   │
│  │ │   throw ApiException.conflict()                │ │   │
│  │ └─────────────────────────────────────────────────┘ │   │
│  │                                                        │   │
│  │ [Step 7] Reserve Inventory (Distributed Lock)         │   │
│  │ ┌─────────────────────────────────────────────────┐ │   │
│  │ │ Sort items by productId (deadlock prevention)  │ │   │
│  │ │                                                 │ │   │
│  │ │ for each item (sorted):                        │ │   │
│  │ │   inventoryService.reserveWithLock(pid, qty)  │ │   │
│  │ │   @DistributedLock(key = "product:X:lock")   │ │   │
│  │ │                                                 │ │   │
│  │ │   → Acquire Redis lock (Redisson)              │ │   │
│  │ │     LeaseTime: 10s, WaitTime: 2s              │ │   │
│  │ │                                                 │ │   │
│  │ │   → Check Redis stock cache                    │ │   │
│  │ │                                                 │ │   │
│  │ │   → invPort.reserve(productId, qty, null)    │ │   │
│  │ │     • InventoryEntity.decreaseStock(qty)      │ │   │
│  │ │       (Optimistic Lock @Version)              │ │   │
│  │ │     • On version conflict: retry (max 3x)     │ │   │
│  │ │     • SpringStockMovementJpa.save()           │ │   │
│  │ │       (with TEMP reference ID)               │ │   │
│  │ │                                                 │ │   │
│  │ │   → Release Redis lock                         │ │   │
│  │ │                                                 │ │   │
│  │ │   → Update Redis cache (async after commit)    │ │   │
│  │ └─────────────────────────────────────────────────┘ │   │
│  │                                                        │   │
│  │ [Step 8] Process Payment (Debit Wallet)               │   │
│  │ ┌─────────────────────────────────────────────────┐ │   │
│  │ │ walletService.debit(...)                       │ │   │
│  │ │ @Transactional (nested propagation=REQUIRED)  │ │   │
│  │ │                                                 │ │   │
│  │ │ • findTxByIdempotency("ORDER:"+requestKey)    │ │   │
│  │ │   (check if already debited)                  │ │   │
│  │ │                                                 │ │   │
│  │ │ • lockByUserId(userId)                         │ │   │
│  │ │   @Lock(PESSIMISTIC_WRITE) - Wallet row lock │ │   │
│  │ │                                                 │ │   │
│  │ │ • Verify balance >= total                      │ │   │
│  │ │   throw if insufficient                        │ │   │
│  │ │                                                 │ │   │
│  │ │ • saveDebitTx(userId, amount, newBalance)    │ │   │
│  │ │   → SpringWalletTxJpa.save()                  │ │   │
│  │ │   → UNIQUE(user_id, idempotency_key)         │ │   │
│  │ │                                                 │ │   │
│  │ │ • updateBalance(userId, newBalance)           │ │   │
│  │ │   → SpringWalletJpa.save()                    │ │   │
│  │ └─────────────────────────────────────────────────┘ │   │
│  │                                                        │   │
│  │ [Step 9] Create Order Entity                          │   │
│  │ ┌─────────────────────────────────────────────────┐ │   │
│  │ │ orderWritePort.createReservedOrder(...)        │ │   │
│  │ │ ↓                                                │ │   │
│  │ │ OrderPersistenceAdapter                         │ │   │
│  │ │                                                 │ │   │
│  │ │ • Create OrderEntity(status=RESERVED)          │ │   │
│  │ │   SpringOrderJpa.save()                        │ │   │
│  │ │                                                 │ │   │
│  │ │ • Create OrderItemEntity for each item         │ │   │
│  │ │   SpringOrderItemJpa.save(each)                │ │   │
│  │ │                                                 │ │   │
│  │ │ • Update stock_movements refId                 │ │   │
│  │ │   TEMP:uuid → actual orderId                  │ │   │
│  │ │                                                 │ │   │
│  │ │ • Return orderId                                │ │   │
│  │ └─────────────────────────────────────────────────┘ │   │
│  │                                                        │   │
│  │ TRANSACTION COMMITTED                                 │   │
│  │ (all changes: inventory, wallet, order, movements)   │   │
│  └────────────────────────────────────────────────────────┘   │
│                                                                 │
│  On Exception:                                                 │
│  ├─ ObjectOptimisticLockingFailureException                  │
│  │   └─ Auto-retry (3 times, exponential backoff)           │
│  ├─ Inventory insufficient                                    │
│  │   └─ Rollback all                                         │
│  ├─ Wallet insufficient                                       │
│  │   └─ Rollback all                                         │
│  ├─ Coupon invalid                                            │
│  │   └─ Rollback all                                         │
│  └─ Any other exception                                       │
│     └─ Rollback all                                          │
└──────────────────────────────────────────────────────────────┘
           │
           ▼
┌──────────────────────────────────────────────────────────────┐
│ Return CreateOrderResponse                                   │
│  {                                                            │
│    orderId,                                                  │
│    status: "RESERVED",                                       │
│    subtotal: { amount, currency },                           │
│    discount: { amount, currency },                           │
│    total: { amount, currency }                              │
│  }                                                            │
└──────────────────────────────────────────────────────────────┘
           │
           ▼
┌─────────────┐
│   Client    │
│ HTTP 201    │
└─────────────┘
```

---

## Order Completion Flow - Event Publishing

```
HTTP PATCH /api/v1/orders/{orderId}/complete
    │
    ▼
OrderController.complete(orderId)
    │
    ▼
CompleteOrderUseCase.complete(orderId)
    │
    ▼
┌────────────────────────────────────────────────────────────────┐
│ OrderService.complete(orderId)                                  │
│ @Service @Transactional                                         │
│                                                                  │
│ [Step 1] Mark Order Completed                                   │
│ ┌────────────────────────────────────────────────────────────┐ │
│ │ orderWritePort.markOrderCompleted(orderId)                │ │
│ │ ↓                                                           │ │
│ │ OrderPersistenceAdapter                                   │ │
│ │ ↓                                                           │ │
│ │ SpringOrderJpa.findById(orderId)                          │ │
│ │ OrderEntity.status = "COMPLETED"                          │ │
│ │ OrderEntity.updatedAt = now()                             │ │
│ │ SpringOrderJpa.save()                                     │ │
│ │                                                            │ │
│ │ Fetch OrderItemEntity list                               │ │
│ │ ↓                                                           │ │
│ │ Return OrderSummary                                       │ │
│ └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│ [Step 2] Publish Event (Outbox Pattern)                         │
│ ┌────────────────────────────────────────────────────────────┐ │
│ │ Create OrderCompletedEvent                                │ │
│ │  {                                                          │ │
│ │    orderId, userId, subtotal, discount, total,           │ │
│ │    requestKey, completedAt, items[]                      │ │
│ │  }                                                          │ │
│ │                                                            │ │
│ │ orderEventPublisher.publish(event)                       │ │
│ │ ↓                                                           │ │
│ │ OutboxOrderEventPublisher                                │ │
│ │ ↓                                                           │ │
│ │ ObjectMapper.writeValueAsString(event)                   │ │
│ │ ↓ (serialize to JSON)                                     │ │
│ │ Create OutboxEventEntity                                 │ │
│ │  {                                                          │ │
│ │    aggregateType: "order",                               │ │
│ │    aggregateId: orderId,                                 │ │
│ │    eventType: "ORDER_COMPLETED",                         │ │
│ │    payload: "{...json...}",                              │ │
│ │    status: PENDING,                                      │ │
│ │    retryCount: 0,                                        │ │
│ │    nextRetryAt: now(),                                   │ │
│ │    lastError: null,                                      │ │
│ │    sentAt: null,                                         │ │
│ │    createdAt: now(),                                     │ │
│ │    updatedAt: now()                                      │ │
│ │  }                                                          │ │
│ │                                                            │ │
│ │ SpringOutboxEventJpa.save(entity)                        │ │
│ │ ↓ INSERT into outbox_events                              │ │
│ └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│ TRANSACTION COMMITTED                                            │
│ (order status + outbox event both persisted)                   │
└────────────────────────────────────────────────────────────────┘
    │
    ▼
Return CompleteOrderResponse
    │
    ▼
HTTP 200 OK
```

---

## Outbox Dispatcher - Event Processing (Scheduled Task)

```
┌───────────────────────────────────────────────────────────────────┐
│ OutboxEventDispatcher                                             │
│                                                                   │
│ @Component                                                        │
│ @Scheduled(fixedDelayString = "${outbox.dispatcher.interval-ms}") │
│ Default: every 5 seconds                                         │
│                                                                   │
│ public void dispatch()                                            │
└────────────┬────────────────────────────────────────────────────┬┘
             │                                                   │
             ▼                                                   │
┌──────────────────────────────────────┐                        │
│ Check enabled flag                   │                        │
│ if (!enabled) return;                │                        │
└──────────────────────────────────────┘                        │
             │                                                   │
             ▼                                                   │
┌──────────────────────────────────────────────────────────────┐ │
│ Query Outbox Events                                          │ │
│                                                               │ │
│ SELECT * FROM outbox_events                                │ │
│ WHERE status = 'PENDING'                                   │ │
│   AND next_retry_at <= now()                              │ │
│ ORDER BY id ASC                                             │ │
│ LIMIT 20 (configurable: batch-size)                        │ │
│                                                               │ │
│ Index: idx_outbox_status_next(status, next_retry_at)      │ │
│ (fast filtering)                                             │ │
└──────────────────────────────────────────────────────────────┘ │
             │                                                   │
             ▼                                                   │
┌──────────────────────────────────────┐                        │
│ If empty, return early               │                        │
└──────────────────────────────────────┘                        │
             │                                                   │
             ▼                                                   │
┌──────────────────────────────────────────────────────────────┐ │
│ For Each OutboxEventEntity:                                  │ │
│                                                               │ │
│ ┌────────────────────────────────────────────────────────┐  │ │
│ │ [Attempt 1] Send Message                              │  │ │
│ │                                                         │  │ │
│ │ try {                                                  │  │ │
│ │   messageProducer.send(                              │  │ │
│ │     eventType,    // "ORDER_COMPLETED"              │  │ │
│ │     payload       // JSON string                     │  │ │
│ │   )                                                    │  │ │
│ │   ↓                                                    │  │ │
│ │   OutboundMessageProducer (interface)               │  │ │
│ │   ↓                                                    │  │ │
│ │   MockMessageProducer (current impl)                │  │ │
│ │   → System.out.println() / log                      │  │ │
│ │   → (Could be: Kafka, RabbitMQ, HTTP webhook)       │  │ │
│ │                                                         │  │ │
│ │ } catch (Exception ex) {                            │  │ │
│ │   → handleFailure(event, ex)                        │  │ │
│ │ }                                                     │  │ │
│ │                                                         │  │ │
│ │ ┌──────────────────────────────────────────────────┐│ │
│ │ │ [On Success]                                      ││ │
│ │ │ ├─ event.status = SENT                           ││ │
│ │ │ ├─ event.sentAt = now()                          ││ │
│ │ │ ├─ event.lastError = null                        ││ │
│ │ │ └─ SpringOutboxEventJpa.save(event)              ││ │
│ │ │                                                    ││ │
│ │ │ Status → PENDING becomes SENT (terminal state)   ││ │
│ │ └──────────────────────────────────────────────────┘│ │
│ │                                                         │  │
│ │ ┌──────────────────────────────────────────────────┐│ │
│ │ │ [On Failure] handleFailure()                     ││ │
│ │ │                                                    ││ │
│ │ │ event.retryCount++                               ││ │
│ │ │ event.lastError = ex.getMessage()                ││ │
│ │ │                                                    ││ │
│ │ │ if (retryCount >= maxRetry) {  // default: 5    ││ │
│ │ │   event.status = FAILED                          ││ │
│ │ │   log.error("Event failed after max retries")   ││ │
│ │ │ } else {                                          ││ │
│ │ │   event.status = PENDING (stay)                  ││ │
│ │ │   delay = retryDelaySeconds * retryCount        ││ │
│ │ │           // 30s * 1 = 30s (1st retry)          ││ │
│ │ │           // 30s * 2 = 60s (2nd retry)          ││ │
│ │ │           // 30s * 3 = 90s (3rd retry)          ││ │
│ │ │           // ...                                  ││ │
│ │ │   event.nextRetryAt = now() + delay             ││ │
│ │ │ }                                                  ││ │
│ │ │                                                    ││ │
│ │ │ SpringOutboxEventJpa.save(event)                 ││ │
│ │ │                                                    ││ │
│ │ │ Retry Sequence:                                   ││ │
│ │ │   Now      → PENDING (retry_count=1, next=30s)  ││ │
│ │ │   +30s     → PENDING (retry_count=2, next=60s)  ││ │
│ │ │   +60s     → PENDING (retry_count=3, next=90s)  ││ │
│ │ │   +90s     → PENDING (retry_count=4, next=120s) ││ │
│ │ │   +120s    → PENDING (retry_count=5, next=150s) ││ │
│ │ │   +150s    → FAILED (max retries exceeded)       ││ │
│ │ └──────────────────────────────────────────────────┘│ │
│ └────────────────────────────────────────────────────────┘  │
│                                                               │
│ @Transactional                                              │
│ (entire dispatch batch is atomic – all or nothing)          │
└──────────────────────────────────────────────────────────────┘ │
                                                                 │
             ┌─────────────────────────────────────────────────┘
             │
             ▼ (waits 5 seconds, then repeats)
    ┌─────────────────────┐
    │ Next Dispatch Cycle │
    │ (every 5 seconds)   │
    └─────────────────────┘
```

---

## Concurrency Control Mechanisms - Visual

```
┌─────────────────────────────────────────────────────────────────┐
│ Three-Level Locking Strategy in Order Creation                  │
└─────────────────────────────────────────────────────────────────┘

LEVEL 1: Database Pessimistic Lock (Wallet)
┌──────────────────────────────────────────────────────────────┐
│ WalletService.debit()                                         │
│                                                               │
│ SELECT * FROM wallets WHERE user_id = ? FOR UPDATE          │
│ (MySQL: acquires exclusive row lock)                         │
│                                                               │
│ ┌────────────────────────────────────────────────────────┐  │
│ │ Thread A                      │ Thread B               │  │
│ ├───────────────────────────────┼───────────────────────┤  │
│ │ LOCK acquired (blocking)      │ WAITING for lock      │  │
│ │ Read balance: 1000            │ (blocked)             │  │
│ │ Check: 1000 >= 500 ✓          │                       │  │
│ │ Debit: 1000 - 500 = 500       │                       │  │
│ │ COMMIT                        │ RELEASED              │  │
│ │ LOCK released                 │ LOCK acquired         │  │
│ │                               │ Read balance: 500     │  │
│ │                               │ (exact balance after  │  │
│ │                               │  Thread A's debit)    │  │
│ └────────────────────────────────────────────────────────┘  │
│                                                               │
│ Guarantees: Serialized updates, no race conditions           │
│ Cost: Can cause lock contention under high load             │
└──────────────────────────────────────────────────────────────┘

LEVEL 2: Optimistic Lock with Retry (Inventory)
┌──────────────────────────────────────────────────────────────┐
│ InventoryPersistenceAdapter.reserve()                         │
│                                                               │
│ @Entity                                                      │
│ class InventoryEntity {                                      │
│   @Version                                                  │
│   private Long version = 1;                                 │
│   private int stock = 100;                                  │
│ }                                                            │
│                                                               │
│ @Retryable(maxAttempts = 3,                                │
│     retryFor = ObjectOptimisticLockingFailureException)     │
│                                                               │
│ ┌────────────────────────────────────────────────────────┐  │
│ │ Thread A              │ Thread B                       │  │
│ ├───────────────────────┼────────────────────────────────┤  │
│ │ SELECT version=1      │ SELECT version=1              │  │
│ │ stock=100             │ stock=100                      │  │
│ │ (no lock held)        │ (no lock held)                 │  │
│ │                       │                                │  │
│ │ stock -= 50           │ stock -= 60                    │  │
│ │ (stock = 50)          │ (stock = 40)                   │  │
│ │                       │                                │  │
│ │ UPDATE with           │ UPDATE with                    │  │
│ │ WHERE version=1       │ WHERE version=1               │  │
│ │ version -> 2          │ (FAILS!)                       │  │
│ │ (success)             │                                │  │
│ │                       │ Error: OptimisticLocking       │  │
│ │                       │ Retry from Step 1              │  │
│ │                       │ SELECT version=2               │  │
│ │                       │ stock=50 (updated by A)        │  │
│ │                       │ (recalculate based on new data)│  │
│ └────────────────────────────────────────────────────────┘  │
│                                                               │
│ Guarantees: Detects concurrent modifications, automatic retry│
│ Cost: Potentially multiple database queries, but good        │
│       throughput for low-contention scenarios               │
└──────────────────────────────────────────────────────────────┘

LEVEL 3: Distributed Lock (Redis/Redisson)
┌──────────────────────────────────────────────────────────────┐
│ InventoryService.reserveWithLock()                            │
│                                                               │
│ @DistributedLock(key = "'product:' + #productId + ':order:lock'",
│                  waitTime = 2000,      // 2 seconds         │
│                  leaseTime = 10000)    // 10 seconds        │
│                                                               │
│ Redisson RLock: SET product:123:order:lock <UUID> NX EX 10 │
│                                                               │
│ ┌────────────────────────────────────────────────────────┐  │
│ │ Process A (Server 1) │ Process B (Server 2)           │  │
│ ├──────────────────────┼────────────────────────────────┤  │
│ │ Lock acquired        │ WAITING for lock               │  │
│ │ (Redis: key set)     │ (polling every 100ms)          │  │
│ │                      │                                 │  │
│ │ Check cache: stock=X │ Wait...                        │  │
│ │ If stock < qty:      │                                 │  │
│ │   throw error        │ Wait...                        │  │
│ │                      │                                 │  │
│ │ DB reserve call      │ Wait...                        │  │
│ │ TX commit           │                                 │  │
│ │ Release lock        │ Lock acquired (2 sec later)    │  │
│ │ (Redis: key deleted) │ Check cache: stock=Y           │  │
│ │                      │ (same data, or different?)     │  │
│ │                      │ DB reserve call                │  │
│ │                      │ TX commit                      │  │
│ │                      │ Release lock                   │  │
│ │                      │                                 │  │
│ │ ┌──────────────────────────────────────────────────┐ │  │
│ │ │ Timeout Scenario:                               │ │  │
│ │ │ If lock not acquired within 2s:                │ │  │
│ │ │ → LockAcquisitionException                    │ │  │
│ │ │ → HTTP 429 / 503 (backoff & retry client-side)│ │  │
│ │ └──────────────────────────────────────────────────┘ │  │
│ └────────────────────────────────────────────────────────┘  │
│                                                               │
│ Guarantees: Cross-process synchronization, timeout handling │
│ Cost: Network latency (Redis), but essential for distributed│
│       systems with multiple servers                         │
└──────────────────────────────────────────────────────────────┘

Summary:
┌─────────────────────────────────────────────────────────────┐
│ Wallet       → Pessimistic (must be serialized)             │
│ Inventory    → Optimistic + Distributed (high throughput)  │
│ Coupon Issue → Distributed (cross-process hotspot)         │
└─────────────────────────────────────────────────────────────┘
```

