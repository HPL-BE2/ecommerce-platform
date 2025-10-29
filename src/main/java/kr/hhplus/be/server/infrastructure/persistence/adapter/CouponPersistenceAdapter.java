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
