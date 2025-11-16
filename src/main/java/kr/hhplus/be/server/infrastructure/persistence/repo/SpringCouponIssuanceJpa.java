package kr.hhplus.be.server.infrastructure.persistence.repo;

import kr.hhplus.be.server.infrastructure.persistence.entity.CouponIssuanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringCouponIssuanceJpa extends JpaRepository<CouponIssuanceEntity, Long> {
    Optional<CouponIssuanceEntity> findByCouponIdAndUserId(Long couponId, Long userId);
    long countByCouponId(Long couponId);

    @Query("select ci.userId from CouponIssuanceEntity ci where ci.couponId = :couponId")
    List<Long> findIssuedUserIds(@Param("couponId") Long couponId);
}
