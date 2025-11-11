package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.application.port.in.RequestCouponIssueUseCase;
import kr.hhplus.be.server.domain.model.Coupon;
import kr.hhplus.be.server.domain.port.out.CouponReadWritePort;
import kr.hhplus.be.server.infrastructure.coupon.CouponIssueLuaScriptExecutor;
import kr.hhplus.be.server.infrastructure.coupon.CouponIssueMessage;
import kr.hhplus.be.server.infrastructure.coupon.CouponIssueMessagePublisher;
import kr.hhplus.be.server.infrastructure.coupon.CouponRedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AsyncCouponIssueService implements RequestCouponIssueUseCase {

    private final CouponReadWritePort couponPort;
    private final CouponIssueLuaScriptExecutor scriptExecutor;
    private final CouponIssueMessagePublisher messagePublisher;
    private final RedisTemplate<String, String> counterRedisTemplate;

    @Override
    public Result request(Command command) {
        if (command.couponId() == null || command.userId() == null) {
            throw new IllegalArgumentException("couponId와 userId는 필수입니다.");
        }

        Coupon coupon = couponPort.findById(command.couponId())
                .orElseThrow(() -> new IllegalArgumentException("쿠폰을 찾을 수 없습니다: couponId=" + command.couponId()));

        validateCoupon(coupon, command.userId());
        ensureRedisSeeds(coupon);

        var decision = scriptExecutor.tryAcquire(command.couponId(), command.userId());
        return switch (decision) {
            case RESERVED -> {
                String requestId = UUID.randomUUID().toString();
                messagePublisher.publish(new CouponIssueMessage(requestId, command.couponId(), command.userId()));
                yield new Result(requestId, "ACCEPTED", "쿠폰 발급이 접수되었습니다.");
            }
            case SOLD_OUT -> throw new IllegalStateException("쿠폰이 모두 소진되었습니다.");
            case DUPLICATE -> throw new IllegalStateException("이미 발급받은 쿠폰입니다.");
            default -> throw new IllegalStateException("쿠폰 발급을 처리할 수 없습니다. 잠시 후 다시 시도해주세요.");
        };
    }

    private void validateCoupon(Coupon coupon, Long userId) {
        if (!coupon.hasIssuanceLimit()) {
            throw new IllegalStateException("비동기 발급은 한정 수량 쿠폰에만 사용할 수 있습니다.");
        }

        if (!coupon.isActive(OffsetDateTime.now())) {
            throw new IllegalStateException("쿠폰 발급 기간이 아닙니다.");
        }

        if (couponPort.isAlreadyIssued(coupon.id(), userId)) {
            throw new IllegalStateException("이미 발급받은 쿠폰입니다.");
        }
    }

    private void ensureRedisSeeds(Coupon coupon) {
        String remainingKey = CouponRedisKeys.remainingCount(coupon.id());
        if (Boolean.FALSE.equals(counterRedisTemplate.hasKey(remainingKey))) {
            long issued = couponPort.countIssuances(coupon.id());
            long remaining = Math.max(coupon.maxIssuance() - issued, 0);
            counterRedisTemplate.opsForValue().set(remainingKey, String.valueOf(remaining));
        }
    }
}
