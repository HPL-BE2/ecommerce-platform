package kr.hhplus.be.server.infrastructure.kafka.coupon;

import java.time.OffsetDateTime;

/**
 * 쿠폰 발급 결과 메시지
 */
public record CouponIssueResult(
        String requestId,
        Long couponId,
        Long userId,
        boolean success,
        String message,
        OffsetDateTime completedAt
) {
}
