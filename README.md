## 2주차 과제
### 필수 과제 - 분석
- [API 명세서](./docs/API%20명세서.pdf)
- [ERD](./docs/ERD.md)
- [인프라 구성도](./docs/인프라_구성도.md)

### 사용자 플로우 (현재 구현 API 기준)
1. **상품 탐색**: `/api/v1/products` 목록/검색과 `/api/v1/products/{productId}` 상세 조회로 구매할 상품과 재고를 확인합니다.
2. **지갑 충전**: `/api/v1/wallets/{userId}/topups` 로 사용자의 지갑 잔액을 충전하고, `Idempotency-Key` 로 멱등성을 보장합니다.
3. **주문 생성**: `/api/v1/orders` 에 상품 목록, 쿠폰, 예상 결제 금액, `Idempotency-Key` 를 전달해 주문을 `RESERVED` 상태로 생성합니다.
4. **주문 완료**: 결제 완료 후 `/api/v1/orders/{orderId}/complete` 를 호출하여 주문을 확정하고 완료 이벤트를 발행합니다.



---
## 프로젝트

## Getting Started

### Prerequisites

#### Running Docker Containers

`local` profile 로 실행하기 위하여 인프라가 설정되어 있는 Docker 컨테이너를 실행해주셔야 합니다.

```bash
docker-compose up -d
```
