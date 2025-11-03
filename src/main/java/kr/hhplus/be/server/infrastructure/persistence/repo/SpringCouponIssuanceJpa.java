package kr.hhplus.be.server.infrastructure.persistence.repo;

import kr.hhplus.be.server.infrastructure.persistence.entity.CouponIssuanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringCouponIssuanceJpa extends JpaRepository<CouponIssuanceEntity, Long> {
    Optional<CouponIssuanceEntity> findByCouponIdAndUserId(Long couponId, Long userId);
    long countByCouponId(Long couponId);
}
