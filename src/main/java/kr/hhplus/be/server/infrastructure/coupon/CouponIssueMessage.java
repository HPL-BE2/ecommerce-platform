package kr.hhplus.be.server.infrastructure.coupon;

public record CouponIssueMessage(String requestId, Long couponId, Long userId) { }
