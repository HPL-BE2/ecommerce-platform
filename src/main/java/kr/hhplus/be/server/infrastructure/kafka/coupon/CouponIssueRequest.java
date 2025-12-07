package kr.hhplus.be.server.infrastructure.kafka.coupon;

import java.time.OffsetDateTime;

/**
 * 쿠폰 발급 요청 메시지
 */
public record CouponIssueRequest(
        String requestId,
        Long couponId,
        Long userId,
        OffsetDateTime requestedAt
) {
}
