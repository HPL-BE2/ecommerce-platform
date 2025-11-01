# Ecommerce Platform

## Overview
This project is a Spring Boot 3 application that exposes a small but production-style ecommerce API. It follows a layered/hexagonal layout with REST controllers, application services, domain models, and infrastructure adapters that collaborate to deliver product browsing, wallet top-ups, and order orchestration features. Caching is enabled globally and the application logs the supported endpoints on startup for quick visibility.【F:src/main/java/kr/hhplus/be/server/ServerApplication.java†L15-L47】【F:src/main/java/kr/hhplus/be/server/application/service/ProductService.java†L15-L50】【F:src/main/java/kr/hhplus/be/server/application/service/OrderService.java†L26-L148】【F:src/main/java/kr/hhplus/be/server/application/service/WalletService.java†L12-L55】【F:src/main/java/kr/hhplus/be/server/infrastructure/config/CacheConfig.java†L16-L33】

## Key capabilities
- **Product catalogue APIs** – Cursor-based listing and detail retrieval, both cached in Redis for faster repeat access.【F:src/main/java/kr/hhplus/be/server/application/service/ProductService.java†L22-L50】【F:src/main/java/kr/hhplus/be/server/interfaces/web/ProductsController.java†L15-L57】
- **Wallet top-up workflow** – Idempotent wallet crediting with row-level locking/creation, overflow protection, and transaction history persistence before balances are updated.【F:src/main/java/kr/hhplus/be/server/application/service/WalletService.java†L17-L55】【F:src/main/java/kr/hhplus/be/server/interfaces/web/WalletController.java†L10-L27】
- **Order reservation & completion** – Idempotent order creation validates stock, coupon usage, and totals before reserving inventory; order completion emits outbox events for downstream systems.【F:src/main/java/kr/hhplus/be/server/application/service/OrderService.java†L36-L147】【F:src/main/java/kr/hhplus/be/server/interfaces/web/OrderController.java†L11-L44】【F:src/main/java/kr/hhplus/be/server/infrastructure/outbox/OutboxEventDispatcher.java†L17-L81】
- **Caching & infrastructure settings** – Redis-backed cache regions, Swagger toggles, and outbox dispatch behaviour are configurable via application profiles.【F:src/main/java/kr/hhplus/be/server/infrastructure/config/CacheConfig.java†L16-L33】【F:src/main/resources/application.yml†L1-L67】
- **Concurrency & scaling considerations** – Supporting documents capture planned approaches for locking, Redis usage, and streaming analytics integrations.【F:docs/claude-code/concurrency-control-design.md†L17-L133】【F:docs/인프라_구성도.md†L1-L39】

## Architecture
- **Interfaces (Adapters in)** – REST controllers convert HTTP contracts to application use cases, returning a consistent API envelope.【F:src/main/java/kr/hhplus/be/server/interfaces/web/ProductsController.java†L15-L57】【F:src/main/java/kr/hhplus/be/server/interfaces/web/OrderController.java†L11-L44】【F:src/main/java/kr/hhplus/be/server/interfaces/web/WalletController.java†L10-L27】
- **Application services (Use cases)** – Coordinate domain logic, caching, idempotency, and integration with outbound ports.【F:src/main/java/kr/hhplus/be/server/application/service/ProductService.java†L15-L50】【F:src/main/java/kr/hhplus/be/server/application/service/OrderService.java†L26-L148】【F:src/main/java/kr/hhplus/be/server/application/service/WalletService.java†L12-L55】
- **Domain model** – Immutable records capture core aggregates such as products, orders, and wallets; application services transform these into DTOs for the API layer.【F:src/main/java/kr/hhplus/be/server/domain/model/Product.java†L1-L14】【F:src/main/java/kr/hhplus/be/server/domain/model/OrderModels.java†L1-L74】【F:src/main/java/kr/hhplus/be/server/domain/model/Wallet.java†L1-L20】
- **Infrastructure (Adapters out)** – Persistence adapters, Redis cache configuration, and the outbox dispatcher integrate with MySQL, Redis, and external messaging.【F:src/main/java/kr/hhplus/be/server/infrastructure/persistence/adapter/OrderPersistenceAdapter.java†L1-L181】【F:src/main/java/kr/hhplus/be/server/infrastructure/config/CacheConfig.java†L16-L33】【F:src/main/java/kr/hhplus/be/server/infrastructure/outbox/OutboxEventDispatcher.java†L17-L81】

## Data model & seeding
- SQL DDL under `schema.sql` provisions users, wallets, catalog, coupon, and order-related tables with indexes and foreign keys.【F:src/main/resources/schema.sql†L1-L120】
- `data.sql` seeds example users, products, inventory, and coupons and keeps the data idempotent through upserts.【F:src/main/resources/data.sql†L1-L74】
- The ERD and focused diagrams in `/docs` provide a visual reference for the domain relationships.【F:docs/ERD.md†L1-L6】

## API surface
| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| GET | `/api/v1/products` | Paged product catalogue with optional search, category filter, and cursor-based pagination.【F:src/main/java/kr/hhplus/be/server/interfaces/web/ProductsController.java†L22-L42】 |
| GET | `/api/v1/products/{productId}` | Fetch a product’s detail view and pricing.【F:src/main/java/kr/hhplus/be/server/interfaces/web/ProductsController.java†L44-L57】 |
| POST | `/api/v1/wallets/{userId}/topups` | Credit a wallet balance idempotently and return the new balance plus idempotency flag.【F:src/main/java/kr/hhplus/be/server/interfaces/web/WalletController.java†L16-L27】 |
| POST | `/api/v1/orders` | Reserve an order with validated items, coupon, and expected total (requires `Idempotency-Key`).【F:src/main/java/kr/hhplus/be/server/interfaces/web/OrderController.java†L18-L38】 |
| PATCH | `/api/v1/orders/{orderId}/complete` | Mark a reserved order completed and trigger outbox publication.【F:src/main/java/kr/hhplus/be/server/interfaces/web/OrderController.java†L40-L44】【F:src/main/java/kr/hhplus/be/server/application/service/OrderService.java†L118-L147】 |

OpenAPI/Swagger UI is enabled automatically on the `local` profile so the endpoints can be explored in a browser while developing.【F:src/main/resources/application.yml†L32-L67】

## Local development
1. **Start infrastructure dependencies** (MySQL 8, Redis 7, optional RedisInsight) via Docker Compose:
   ```bash
   docker-compose up -d
   ```
   The compose file exposes the default ports and configures credentials that match the Spring `local` profile.【F:docker-compose.yml†L1-L36】【F:src/main/resources/application.yml†L1-L67】
2. **Run the application** with the local profile (defaults to `local`):
   ```bash
   ./gradlew bootRun
   ```
   Schema and seed data are applied on startup because SQL initialization is set to `always` for the active profile.【F:src/main/resources/application.yml†L14-L26】【F:src/main/resources/data.sql†L1-L74】
3. **Access documentation** at `http://localhost:8080/swagger-ui.html` once the app is running (local profile only).【F:src/main/resources/application.yml†L32-L67】

## Testing
- Execute the full test suite with Testcontainers-managed MySQL using:
  ```bash
  ./gradlew test
  ```
  A reusable MySQL container is started before the Spring context loads and shut down afterwards, ensuring isolation for integration tests.【F:src/test/java/kr/hhplus/be/server/TestcontainersConfiguration.java†L10-L33】
- `UserFlowIntegrationTest` exercises the primary user journey end-to-end: list products, view details, top up the wallet, create an order, complete it, and verify the resulting outbox record.【F:src/test/java/kr/hhplus/be/server/interfaces/web/UserFlowIntegrationTest.java†L28-L162】

## Documentation & further reading
- [API 명세서 (v1, v2)](./docs) – Formal API contracts for reference.
- [ERD](./docs/ERD.md) – Entity relationship diagrams for the relational schema.【F:docs/ERD.md†L1-L6】
- [인프라 구성도](./docs/인프라_구성도.md) – High-level infrastructure topology and component responsibilities.【F:docs/인프라_구성도.md†L1-L39】
- [동시성 제어 설계](./docs/claude-code/concurrency-control-design.md) – Strategy document outlining locking, Redis usage, and test plans for scaling the service.【F:docs/claude-code/concurrency-control-design.md†L17-L156】

## Project layout
```
src/
├── main
│   ├── java/kr/hhplus/be/server
│   │   ├── interfaces/web        # REST entry points【F:src/main/java/kr/hhplus/be/server/interfaces/web/ProductsController.java†L15-L57】
│   │   ├── application/service   # Use case orchestration【F:src/main/java/kr/hhplus/be/server/application/service/OrderService.java†L26-L148】
│   │   ├── domain/model          # Core entities & value objects【F:src/main/java/kr/hhplus/be/server/domain/model/OrderModels.java†L1-L74】
│   │   └── infrastructure        # Persistence, cache, outbox adapters【F:src/main/java/kr/hhplus/be/server/infrastructure/outbox/OutboxEventDispatcher.java†L17-L81】
│   └── resources                 # Spring config, schema, seed data【F:src/main/resources/application.yml†L1-L67】【F:src/main/resources/data.sql†L1-L74】
└── test
    └── java/kr/hhplus/be/server  # Integration tests with Testcontainers【F:src/test/java/kr/hhplus/be/server/interfaces/web/UserFlowIntegrationTest.java†L28-L162】
```

## Getting started quickly
1. Clone the repository and install Java 17.
2. Run `docker-compose up -d` to provision MySQL/Redis.
3. Launch the application with `./gradlew bootRun` and visit Swagger UI.
4. Execute `./gradlew test` to verify the main user flows with Testcontainers.
