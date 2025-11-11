package kr.hhplus.be.server.infrastructure.coupon;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class InMemoryCouponIssueMessagePublisher implements CouponIssueMessagePublisher {

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void publish(CouponIssueMessage message) {
        log.info("[CouponIssuePublisher] 쿠폰 발급 메시지 publish requestId={} couponId={} userId={}",
                message.requestId(), message.couponId(), message.userId());
        eventPublisher.publishEvent(message);
    }
}
