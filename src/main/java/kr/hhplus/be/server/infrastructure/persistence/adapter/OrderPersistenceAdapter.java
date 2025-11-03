package kr.hhplus.be.server.infrastructure.persistence.adapter;

import kr.hhplus.be.server.domain.model.OrderModels;
import kr.hhplus.be.server.domain.port.out.CouponValidatePort;
import kr.hhplus.be.server.domain.port.out.InventoryReservePort;
import kr.hhplus.be.server.domain.port.out.OrderWritePort;
import kr.hhplus.be.server.domain.port.out.ProductPricePort;
import kr.hhplus.be.server.infrastructure.persistence.entity.OrderEntity;
import kr.hhplus.be.server.infrastructure.persistence.entity.OrderItemEntity;
import kr.hhplus.be.server.infrastructure.persistence.entity.StockMovementEntity;
import kr.hhplus.be.server.infrastructure.persistence.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@Transactional
@RequiredArgsConstructor
public class OrderPersistenceAdapter implements ProductPricePort, InventoryReservePort, CouponValidatePort, OrderWritePort {
    private final SpringProductPriceJpa priceJpa;
    private final SpringInventoryJpa invJpa;
    private final SpringOrderJpa orderJpa;
    private final SpringOrderItemJpa orderItemJpa;
    private final SpringStockMovementJpa movementJpa;
    private final SpringCouponJpa couponJpa;
    private final SpringCouponIssuanceJpa issuanceJpa;

    // 트랜잭션별 임시 참조 ID 저장 (재고 이력 추적용)
    private static final ThreadLocal<String> TEMP_REF_ID = new ThreadLocal<>();

    // --- InventoryReservePort ---
    @Override
    public List<OrderModels.Inventory> lockInventories(List<Long> productIds) {
        return invJpa.lockByProductIds(productIds).stream()
                .map(e -> new OrderModels.Inventory(e.getProductId(), e.getStock())).toList();
    }

    @Override
    @Retryable(
            retryFor = {ObjectOptimisticLockingFailureException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2)
    )
    public void reserve(Long productId, int qty, Long orderId) {
        // Optimistic Lock 사용: @Version 필드로 동시성 제어
        var inv = invJpa.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("재고를 찾을 수 없습니다: productId=" + productId));

        // 재고 감소 (InventoryEntity.decreaseStock() 내부에서 재고 검증)
        inv.decreaseStock(qty);

        // 재고 변경 저장 (Optimistic Lock - version 자동 증가)
        invJpa.save(inv);

        // 재고 이력 기록
        String reference;
        if (orderId != null) {
            reference = String.valueOf(orderId);
        } else {
            // orderId가 null이면 트랜잭션별 고유 임시 키 생성/사용
            reference = TEMP_REF_ID.get();
            if (reference == null) {
                reference = "TEMP:" + UUID.randomUUID().toString();
                TEMP_REF_ID.set(reference);
            }
        }
        movementJpa.save(new StockMovementEntity(productId, -qty, "RESERVE", reference));
    }

    // --- ProductPricePort ---
    @Override
    public List<OrderModels.ProductPrice> loadPrices(List<Long> productIds) {
        // priceJpa.findActiveWithPrice(productIds) // 가격 변동 이력 고려 시
        return priceJpa.findPrice(productIds).stream()
                .map(v -> new OrderModels.ProductPrice(v.getId(), v.getName(), v.getPrice())).toList();
    }

    // --- CouponValidatePort ---
    @Override
    public Optional<OrderModels.CouponInfo> findApplicable(Long userId, String couponCode, int subtotal) {
        var c = couponJpa.findByCode(couponCode).orElse(null);
        if (c == null) return Optional.empty();
        var nowValid = (c.getStartsAt()==null || c.getStartsAt().isBefore(java.time.OffsetDateTime.now()))
                && (c.getEndsAt()==null || c.getEndsAt().isAfter(java.time.OffsetDateTime.now()));
        if (!nowValid) return Optional.empty();
        var iss = issuanceJpa.findByCouponIdAndUserId(c.getId(), userId).orElse(null);
        if (iss == null) return Optional.empty();
        return Optional.of(new OrderModels.CouponInfo(
                c.getId(), c.getCode(), c.getType(), c.getValue(),
                iss.getId(), c.getMinAmount(), c.getMaxDiscount()
        ));
    }

    // --- OrderWritePort ---
    @Override
    public Optional<Long> findOrderIdByRequestKey(Long userId, String requestKey) {
        return orderJpa.findByUserIdAndRequestKey(userId, requestKey).map(OrderEntity::getId);
    }

    @Override
    public Long createReservedOrder(Long userId, List<OrderModels.OrderItem> items, int subtotal, int discount, int total, String requestKey, Long couponIssuanceId) {
        try {
            var o = new OrderEntity();
            o.setUserId(userId);
            o.setOrderNo(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            o.setStatus("RESERVED");
            o.setDiscount((long) discount);
            o.setTotal((long) total);
            o.setRequestKey(requestKey);
            o.setCouponIssuanceId(couponIssuanceId);
            var saved = orderJpa.save(o);

            for (var it : items) {
                var e = new OrderItemEntity();
                e.setOrderId(saved.getId());
                e.setProductId(it.productId());
                e.setName(it.name());
                e.setQty(it.qty());
                e.setUnitPrice((long) it.unitPrice());
                e.setLineTotal((long) it.lineTotal());
                orderItemJpa.save(e);
            }

            // 재고 이력의 refId 업데이트 (임시 키 -> orderId)
            // ThreadLocal에서 임시 키를 가져와서 실제 orderId로 업데이트
            String tempRefId = TEMP_REF_ID.get();
            if (tempRefId != null) {
                int updatedCount = movementJpa.updateRefId(tempRefId, String.valueOf(saved.getId()));
                // updatedCount가 items 개수와 같아야 정상
            }

            return saved.getId();
        } finally {
            // ThreadLocal 정리 (메모리 누수 방지)
            TEMP_REF_ID.remove();
        }
    }

    @Override
    public OrderModels.OrderSummary markOrderCompleted(Long orderId) {
        var order = orderJpa.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: id=" + orderId));

        order.setStatus("COMPLETED");
        order.setUpdatedAt(OffsetDateTime.now());
        var saved = orderJpa.save(order);

        var items = orderItemJpa.findByOrderId(orderId).stream()
                .map(e -> new OrderModels.OrderItem(
                        e.getProductId(),
                        e.getName(),
                        Math.toIntExact(e.getUnitPrice()),
                        e.getQty(),
                        Math.toIntExact(e.getLineTotal())
                ))
                .toList();

        int subtotal = items.stream().mapToInt(OrderModels.OrderItem::lineTotal).sum();
        int discount = Math.toIntExact(saved.getDiscount());
        int total = Math.toIntExact(saved.getTotal());

        return new OrderModels.OrderSummary(
                saved.getId(),
                saved.getUserId(),
                saved.getStatus(),
                subtotal,
                discount,
                total,
                saved.getRequestKey(),
                saved.getUpdatedAt(),
                items
        );
    }
}
