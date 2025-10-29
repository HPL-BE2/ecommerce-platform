package kr.hhplus.be.server.domain.port.out.dto;

import java.time.OffsetDateTime;
import java.util.List;

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
