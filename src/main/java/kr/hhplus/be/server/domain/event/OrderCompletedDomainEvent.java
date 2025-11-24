package kr.hhplus.be.server.domain.event;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 주문 완료 도메인 이벤트
 *
 * Spring Application Event로 발행되어 여러 리스너가 구독할 수 있음
 * - 데이터 플랫폼 전송
 * - 랭킹 업데이트
 * - 재고 분석
 * - 알림 발송 등
 */
public record OrderCompletedDomainEvent(
        Long orderId,
        Long userId,
        int subtotal,
        int discount,
        int total,
        String requestKey,
        OffsetDateTime completedAt,
        List<OrderItemSnapshot> items
) {
    public record OrderItemSnapshot(
            Long productId,
            String name,
            int unitPrice,
            int qty,
            int lineTotal
    ) {}
}
