package kr.hhplus.be.server.layered.infrastructure.persistence.repo;

import kr.hhplus.be.server.layered.infrastructure.persistence.entity.InventoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringInventoryJpa extends JpaRepository<InventoryEntity, Long> {
    Optional<InventoryEntity> findById(Long id);
}
