package kr.hhplus.be.server.layered.infrastructure.persistence.repo;

import jakarta.persistence.LockModeType;
import kr.hhplus.be.server.layered.infrastructure.persistence.entity.WalletEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SpringWalletJpa extends JpaRepository<WalletEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WalletEntity w where w.userId = :userId")
    Optional<WalletEntity> lockByUserId(@Param("userId") Long userId);
}
