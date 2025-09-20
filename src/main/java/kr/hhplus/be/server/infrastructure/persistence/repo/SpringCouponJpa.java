package kr.hhplus.be.server.infrastructure.persistence.repo;

import kr.hhplus.be.server.infrastructure.persistence.entity.CouponEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringCouponJpa extends JpaRepository<CouponEntity, Long> {
    Optional<CouponEntity> findByCode(String code);
}
