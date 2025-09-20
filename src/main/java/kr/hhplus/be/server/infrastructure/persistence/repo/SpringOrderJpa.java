package kr.hhplus.be.server.infrastructure.persistence.repo;

import kr.hhplus.be.server.infrastructure.persistence.entity.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringOrderJpa extends JpaRepository<OrderEntity, Long> {
    Optional<OrderEntity> findByUserIdAndRequestKey(Long userId, String requestKey);
}
