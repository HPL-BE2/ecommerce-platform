package kr.hhplus.be.server.infrastructure.persistence.repo;

import kr.hhplus.be.server.infrastructure.persistence.entity.CouponEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface SpringCouponJpa extends JpaRepository<CouponEntity, Long> {
    Optional<CouponEntity> findByCode(String code);

    /**
     * Pessimistic Lock으로 쿠폰 조회 (동시성 제어용)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CouponEntity c WHERE c.id = :id")
    Optional<CouponEntity> findByIdWithLock(@Param("id") Long id);
}
