package kr.hhplus.be.server.infrastructure.persistence.adapter;

import kr.hhplus.be.server.domain.model.Coupon;
import kr.hhplus.be.server.domain.port.out.CouponReadWritePort;
import kr.hhplus.be.server.infrastructure.persistence.entity.CouponEntity;
import kr.hhplus.be.server.infrastructure.persistence.entity.CouponIssuanceEntity;
import kr.hhplus.be.server.infrastructure.persistence.repo.SpringCouponIssuanceJpa;
import kr.hhplus.be.server.infrastructure.persistence.repo.SpringCouponJpa;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
@Transactional
@RequiredArgsConstructor
public class CouponPersistenceAdapter implements CouponReadWritePort {
    private final SpringCouponJpa couponJpa;
    private final SpringCouponIssuanceJpa issuanceJpa;

    @Override
    public Optional<Coupon> findById(Long couponId) {
        return couponJpa.findById(couponId).map(this::toDomain);
    }

    @Override
    public long countIssuances(Long couponId) {
        return issuanceJpa.countByCouponId(couponId);
    }

    @Override
    public Long issueCoupon(Long couponId, Long userId) {
        var entity = new CouponIssuanceEntity();
        entity.setCouponId(couponId);
        entity.setUserId(userId);
        entity.setRedeemCount(0);
        var saved = issuanceJpa.saveAndFlush(entity);
        return saved.getId();
    }

    /**
     * 쿠폰 발급 수량을 원자적으로 증가시키고 성공 여부를 반환
     * Pessimistic Lock을 사용하여 동시성 제어
     *
     * @param couponId 쿠폰 ID
     * @return 발급 성공 여부 (true: 성공, false: 수량 제한 초과)
     */
    public boolean tryIncrementIssuedCount(Long couponId) {
        // Pessimistic Write Lock으로 쿠폰 조회
        var coupon = couponJpa.findByIdWithLock(couponId)
                .orElseThrow(() -> new IllegalArgumentException("쿠폰을 찾을 수 없습니다: couponId=" + couponId));

        // 발급 제한이 있는 경우에만 체크
        if (coupon.getMaxIssuance() != null) {
            if (coupon.getIssuedCount() >= coupon.getMaxIssuance()) {
                return false; // 발급 수량 초과
            }
        }

        // 발급 수량 증가
        coupon.setIssuedCount(coupon.getIssuedCount() + 1);
        couponJpa.save(coupon);
        return true;
    }

    @Override
    public boolean isAlreadyIssued(Long couponId, Long userId) {
        return issuanceJpa.findByCouponIdAndUserId(couponId, userId).isPresent();
    }

    private Coupon toDomain(CouponEntity e) {
        return new Coupon(
                e.getId(),
                e.getCode(),
                e.getType(),
                e.getValue(),
                e.getMinAmount(),
                e.getMaxDiscount(),
                e.getMaxIssuance(),
                e.getStartsAt(),
                e.getEndsAt()
        );
    }
}
