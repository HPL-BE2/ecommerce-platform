package kr.hhplus.be.server.domain.model;

public class OrderModels {
    public record ProductPrice(Long productId, String name, int unitPrice) {}
    public record Inventory(Long productId, int stock) {}
    public record CouponInfo(Long couponId, String code, String type, int value,
                             Long issuanceId, Integer minAmount, Integer maxDiscount) {}
    public record OrderItem(Long productId, String name, int unitPrice, int qty, int lineTotal) {}
    public record OrderSummary(Long orderId, Long userId, String status,
                               int subtotal, int discount, int total, String requestKey,
                               java.time.OffsetDateTime completedAt,
                               java.util.List<OrderItem> items) {}
}
