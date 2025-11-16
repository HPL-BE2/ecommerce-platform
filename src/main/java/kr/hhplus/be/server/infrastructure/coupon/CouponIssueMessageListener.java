package kr.hhplus.be.server.infrastructure.coupon;

import kr.hhplus.be.server.domain.port.out.CouponReadWritePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.RedisTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
public class CouponIssueMessageListener {

    private final CouponReadWritePort couponPort;
    private final RedisTemplate<String, String> counterRedisTemplate;

    @Async
    @EventListener
    public void handle(CouponIssueMessage message) {
        try {
            if (couponPort.isAlreadyIssued(message.couponId(), message.userId())) {
                log.info("[CouponIssueListener] 이미 발급된 요청 무시 requestId={}", message.requestId());
                return;
            }

            Long issuanceId = couponPort.issueCoupon(message.couponId(), message.userId());
            counterRedisTemplate.opsForValue().increment(CouponRedisKeys.issuedCount(message.couponId()));
            log.info("[CouponIssueListener] 쿠폰 발급 완료 requestId={} issuanceId={}", message.requestId(), issuanceId);
        } catch (DataAccessException | IllegalStateException ex) {
            log.error("[CouponIssueListener] 쿠폰 발급 처리 실패 requestId={} couponId={} userId={}",
                    message.requestId(), message.couponId(), message.userId(), ex);
            compensateReservation(message);
            throw ex;
        }
    }

    private void compensateReservation(CouponIssueMessage message) {
        String issuedUsersKey = CouponRedisKeys.issuedUsers(message.couponId());
        String remainingKey = CouponRedisKeys.remainingCount(message.couponId());

        try {
            Long removed = counterRedisTemplate.opsForSet()
                    .remove(issuedUsersKey, String.valueOf(message.userId()));
            if (removed != null && removed > 0) {
                counterRedisTemplate.opsForValue().increment(remainingKey);
                log.info("[CouponIssueListener] 쿠폰 발급 실패로 슬롯 복원 requestId={} couponId={} userId={}",
                        message.requestId(), message.couponId(), message.userId());
            } else {
                log.warn("[CouponIssueListener] 쿠폰 발급 실패 보상 처리 대상 슬롯을 찾을 수 없음 requestId={} couponId={} userId={}",
                        message.requestId(), message.couponId(), message.userId());
            }
        } catch (Exception recoveryEx) {
            log.error("[CouponIssueListener] 쿠폰 발급 실패 보상 처리 중 오류 requestId={} couponId={} userId={}",
                    message.requestId(), message.couponId(), message.userId(), recoveryEx);
        }
    }
}
