package kr.hhplus.be.server.infrastructure.persistence.repo;

import kr.hhplus.be.server.infrastructure.persistence.entity.StockMovementEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SpringStockMovementJpa extends JpaRepository<StockMovementEntity, Long> {

    /**
     * 특정 refId를 가진 StockMovement 목록 조회
     */
    List<StockMovementEntity> findByRefId(String refId);

    /**
     * 특정 refId를 가진 StockMovement들의 refId를 새로운 값으로 업데이트
     */
    @Modifying
    @Query("UPDATE StockMovementEntity sm SET sm.refId = :newRefId WHERE sm.refId = :oldRefId")
    int updateRefId(@Param("oldRefId") String oldRefId, @Param("newRefId") String newRefId);
}
