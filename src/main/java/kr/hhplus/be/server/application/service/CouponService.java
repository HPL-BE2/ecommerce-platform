package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.application.port.in.IssueCouponUseCase;
import kr.hhplus.be.server.domain.model.Coupon;
import kr.hhplus.be.server.domain.port.out.CouponReadWritePort;
import kr.hhplus.be.server.infrastructure.persistence.adapter.CouponPersistenceAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class CouponService implements IssueCouponUseCase {
    private final CouponReadWritePort couponPort;
    private final CouponPersistenceAdapter couponAdapter;
    // TODO: Redis 추가 시 사용
    // private final RedisTemplate<String, Long> redisTemplate;

    @Override
    @Transactional
    public Result issue(Command cmd) {
        if (cmd.couponId() == null || cmd.userId() == null) {
            throw new IllegalArgumentException("couponId와 userId는 필수입니다.");
        }

        // 1) 쿠폰 조회
        Coupon coupon = couponPort.findById(cmd.couponId())
                .orElseThrow(() -> new IllegalArgumentException("쿠폰을 찾을 수 없습니다: couponId=" + cmd.couponId()));

        // 2) 쿠폰 유효성 검증 (기간)
        if (!coupon.isActive(OffsetDateTime.now())) {
            throw new IllegalStateException("쿠폰 발급 기간이 아닙니다: couponId=" + cmd.couponId());
        }

        // 3) 중복 발급 확인
        if (couponPort.isAlreadyIssued(cmd.couponId(), cmd.userId())) {
            throw new IllegalStateException("이미 발급받은 쿠폰입니다: couponId=" + cmd.couponId() + ", userId=" + cmd.userId());
        }

        // 4) 발급 수량 제한 확인 및 원자적 증가 (동시성 제어)
        // Pessimistic Lock을 사용하여 Race Condition 방지
        // TODO: Redis Atomic Counter 사용 시 성능 개선
        // String redisKey = "coupon:" + cmd.couponId() + ":count";
        // Long count = redisTemplate.opsForValue().increment(redisKey);
        // if (count > coupon.maxIssuance()) {
        //     redisTemplate.opsForValue().decrement(redisKey); // 보상
        //     throw new IllegalStateException("쿠폰이 모두 소진되었습니다.");
        // }

        if (coupon.hasIssuanceLimit()) {
            // 원자적으로 수량 증가 시도 (Pessimistic Lock 사용)
            boolean success = couponAdapter.tryIncrementIssuedCount(cmd.couponId());
            if (!success) {
                throw new IllegalStateException("쿠폰이 모두 소진되었습니다: couponId=" + cmd.couponId());
            }
        }

        // 5) 쿠폰 발급
        Long issuanceId;
        try {
            issuanceId = couponPort.issueCoupon(cmd.couponId(), cmd.userId());
        } catch (DataIntegrityViolationException e) {
            // UNIQUE 제약 위반 (동시 요청으로 인한 중복 발급 시도)
            throw new IllegalStateException("이미 발급받은 쿠폰입니다: couponId=" + cmd.couponId() + ", userId=" + cmd.userId());
        }

        return new Result(issuanceId, cmd.couponId(), cmd.userId(), "쿠폰이 발급되었습니다.");
    }
}
