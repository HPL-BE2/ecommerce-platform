package kr.hhplus.be.server.infrastructure.persistence.repo;

import kr.hhplus.be.server.infrastructure.persistence.entity.StockMovementEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringStockMovementJpa extends JpaRepository<StockMovementEntity, Long> {

}
