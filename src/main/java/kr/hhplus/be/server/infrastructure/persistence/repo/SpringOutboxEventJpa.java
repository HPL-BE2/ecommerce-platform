package kr.hhplus.be.server.infrastructure.persistence.repo;

import kr.hhplus.be.server.infrastructure.persistence.entity.OutboxEventEntity;
import kr.hhplus.be.server.infrastructure.persistence.entity.OutboxEventStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface SpringOutboxEventJpa extends JpaRepository<OutboxEventEntity, Long> {
    List<OutboxEventEntity> findByStatusAndNextRetryAtLessThanEqualOrderByIdAsc(OutboxEventStatus status, OffsetDateTime cutoff, Pageable pageable);
}
