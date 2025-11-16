package kr.hhplus.be.server.infrastructure.coupon;

public interface CouponIssueMessagePublisher {
    void publish(CouponIssueMessage message);
}
