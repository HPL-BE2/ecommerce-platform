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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    // --- InventoryReservePort ---
    @Override
    public List<OrderModels.Inventory> lockInventories(List<Long> productIds) {
        return invJpa.lockByProductIds(productIds).stream()
                .map(e -> new OrderModels.Inventory(e.getProductId(), e.getStock())).toList();
    }

    @Override
    public void reserve(Long productId, int qty, Long orderId) {
        var inv = invJpa.findById(productId).orElseThrow();
        invJpa.lockByProductIds(List.of(productId)); // 보강
        if (inv.getStock() < qty) throw new IllegalStateException("재고 부족: " + productId);
        // 감소
        try {
            var f = inv.getClass().getDeclaredField("stock"); // 세터 없는 경우 대응
            f.setAccessible(true);
            f.set(inv, inv.getStock() - qty);
        } catch (Exception ignore) {}
        invJpa.save(inv);
        movementJpa.save(new StockMovementEntity(productId, -qty, "RESERVE", String.valueOf(orderId)));
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
        return saved.getId();
    }
}
