package kr.hhplus.be.server.layered.infrastructure.persistence.repo;

import kr.hhplus.be.server.layered.infrastructure.persistence.entity.WalletTransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringWalletTxJpa extends JpaRepository<WalletTransactionEntity, Long> {
    Optional<WalletTransactionEntity> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
}
