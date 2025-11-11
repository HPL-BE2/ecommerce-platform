package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.application.port.in.CompleteOrderUseCase;
import kr.hhplus.be.server.application.port.in.CreateOrderUseCase;
import kr.hhplus.be.server.application.port.in.CreateWalletDebitUseCase;
import kr.hhplus.be.server.domain.model.OrderModels;
import kr.hhplus.be.server.domain.port.out.CouponValidatePort;
import kr.hhplus.be.server.domain.port.out.InventoryReservePort;
import kr.hhplus.be.server.domain.port.out.OrderEventPublisher;
import kr.hhplus.be.server.domain.port.out.OrderWritePort;
import kr.hhplus.be.server.domain.port.out.ProductPricePort;
import kr.hhplus.be.server.domain.port.out.dto.OrderCompletedEvent;
import kr.hhplus.be.server.interfaces.web.error.ApiException;
import kr.hhplus.be.server.interfaces.web.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@Transactional
@RequiredArgsConstructor
public class OrderService implements CreateOrderUseCase, CompleteOrderUseCase {
    private final ProductPricePort pricePort;
    private final InventoryReservePort invPort;
    private final CouponValidatePort couponPort;
    private final OrderWritePort orderWritePort;
    private final OrderEventPublisher orderEventPublisher;
    private final WalletService walletService;
    private final InventoryService inventoryService;

    @Override
    public CreateOrderUseCase.Result create(CreateOrderUseCase.Command cmd) {
        if (cmd.userId() == null || cmd.items() == null || cmd.items().isEmpty())
            throw new IllegalArgumentException("userId/items는 필수입니다.");
        if (cmd.requestKey() == null || cmd.requestKey().isBlank())
            throw new IllegalArgumentException("Idempotency-Key(requestKey)는 필수입니다.");

        // 0) 멱등 처리
        var existing = orderWritePort.findOrderIdByRequestKey(cmd.userId(), cmd.requestKey());
        if (existing.isPresent()) {
            Long orderId = existing.get();
            // 멱등 재요청: 합계 재계산 없이 저장값을 그대로 읽어오는 방식도 가능.
            // 여기서는 간단히 200/201 일관 응답을 위해 최소 Result만 리턴하도록 가정.
            return new CreateOrderUseCase.Result(orderId, "RESERVED", 0, 0, Optional.ofNullable(cmd.expectedTotal()).orElse(0));
        }

        // 1) 수량/중복 검사
        Map<Long, Integer> qtyMap = new LinkedHashMap<>();
        for (var it : cmd.items()) {
            if (it.qty() == null || it.qty() <= 0) throw new IllegalArgumentException("qty는 1 이상이어야 합니다.");
            qtyMap.merge(it.productId(), it.qty(), Integer::sum);
        }
        List<Long> productIds = new ArrayList<>(qtyMap.keySet());

        // 2) 상품 가격 조회
        var prices = pricePort.loadPrices(productIds);
        if (prices.size() != productIds.size())
            throw new NotFoundException("일부 상품을 찾을 수 없습니다.");

        // 3) 재고 조회 (Optimistic Lock - 락 없이 조회만)
        var invs = invPort.lockInventories(productIds);
        Map<Long, Integer> stockByPid = new HashMap<>();
        invs.forEach(i -> stockByPid.put(i.productId(), i.stock()));

        // 4) 금액 계산
        List<OrderModels.OrderItem> items = new ArrayList<>();
        int subtotal = 0;
        for (var p : prices) {
            int qty = qtyMap.get(p.productId());
            int line = p.unitPrice() * qty;
            items.add(new OrderModels.OrderItem(p.productId(), p.name(), p.unitPrice(), qty, line));
            subtotal += line;
        }

        int discount = 0;
        Long couponIssuanceId = null;
        if (cmd.couponCode() != null && !cmd.couponCode().isBlank()) {
            var couponOpt = couponPort.findApplicable(cmd.userId(), cmd.couponCode(), subtotal);
            if (couponOpt.isEmpty()) throw new IllegalArgumentException("쿠폰이 유효하지 않습니다.");
            var c = couponOpt.get();
            discount = ("PERCENT".equalsIgnoreCase(c.type()))
                    ? Math.min((subtotal * c.value()) / 100, Optional.ofNullable(c.maxDiscount()).orElse(Integer.MAX_VALUE))
                    : c.value();
            if (c.minAmount() != null && subtotal < c.minAmount()) discount = 0;
            couponIssuanceId = c.issuanceId();
        }
        int total = Math.max(0, subtotal - discount);

        if (cmd.expectedTotal() != null && !Objects.equals(cmd.expectedTotal(), total)) {
            // throw new IllegalStateException("요청 expectedTotal(" + cmd.expectedTotal() + ")과 서버 계산값(" + total + ")이 다릅니다.");
            throw ApiException.conflict(
                    "/errors/total-mismatch",
                    "Conflict",
                    "요청 expectedTotal(" + cmd.expectedTotal() + ")과 서버 계산값(" + total + ")이 다릅니다."
            ).with("expected", cmd.expectedTotal())
                    .with("actual", total);
        }

        // 5) 재고 예약(감소) - 주문 생성 이전에 실행 (원자성 보장)
        // 분산락 + Redis 캐시: 상품별 동시성 제어
        // Redis 캐시에서 빠른 실패, DB에서 최종 검증
        // 재고 부족 시 IllegalStateException 발생 → 트랜잭션 롤백
        //
        // [데드락 방지] productId 오름차순으로 정렬하여 락 획득 순서 통일
        // 예시: User A [상품1, 상품2], User B [상품2, 상품1]
        //       정렬 없이는 서로 Lock(1), Lock(2) 대기 → 데드락
        //       정렬하면 모두 Lock(1) → Lock(2) 순서로 통일 → 데드락 방지
        var sortedItems = items.stream()
                .sorted((a, b) -> Long.compare(a.productId(), b.productId()))
                .toList();

        for (var it : sortedItems) {
            inventoryService.reserveWithLock(it.productId(), it.qty(), null); // orderId는 아직 없으므로 null
        }

        // 6) 결제 처리 (잔액 차감) - Pessimistic Lock으로 동시성 제어
        // Idempotency Key: "ORDER:" + requestKey (중복 결제 방지)
        walletService.debit(new CreateWalletDebitUseCase.Command(
                cmd.userId(),
                (long) total,
                "ORDER:" + cmd.requestKey(),  // 멱등키: 주문별 고유
                "ORDER",                       // 참조 타입
                cmd.requestKey()              // 참조 ID (주문 requestKey)
        ));

        // 7) 주문 저장(RESERVED)
        Long orderId = orderWritePort.createReservedOrder(cmd.userId(), items, subtotal, discount, total,
                cmd.requestKey(), couponIssuanceId);

        return new CreateOrderUseCase.Result(orderId, "RESERVED", subtotal, discount, total);
    }

    @Override
    public CompleteOrderUseCase.Result complete(CompleteOrderUseCase.Command command) {
        if (command.orderId() == null) {
            throw new IllegalArgumentException("orderId는 필수입니다.");
        }

        OrderModels.OrderSummary summary = orderWritePort.markOrderCompleted(command.orderId());

        var event = new OrderCompletedEvent(
                summary.orderId(),
                summary.userId(),
                summary.subtotal(),
                summary.discount(),
                summary.total(),
                summary.requestKey(),
                summary.completedAt(),
                summary.items().stream()
                        .map(it -> new OrderCompletedEvent.Item(
                                it.productId(),
                                it.name(),
                                it.unitPrice(),
                                it.qty(),
                                it.lineTotal()
                        ))
                        .toList()
        );

        orderEventPublisher.publish(event);

        return new CompleteOrderUseCase.Result(summary.orderId(), summary.total());
    }
}
