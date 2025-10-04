package kr.hhplus.be.server.infrastructure.persistence.repo;

import jakarta.persistence.LockModeType;
import kr.hhplus.be.server.infrastructure.persistence.entity.InventoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SpringInventoryJpa extends JpaRepository<InventoryEntity, Long> {
    Optional<InventoryEntity> findById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InventoryEntity i where i.productId in :ids")
    List<InventoryEntity> lockByProductIds(@Param("ids") List<Long> productIds);
}
