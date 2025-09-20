package kr.hhplus.be.server.layered.infrastructure.persistence.adapter;

import kr.hhplus.be.server.layered.domain.model.Wallet;
import kr.hhplus.be.server.layered.domain.model.WalletTransaction;
import kr.hhplus.be.server.layered.domain.port.out.WalletReadWritePort;
import kr.hhplus.be.server.layered.infrastructure.persistence.entity.WalletEntity;
import kr.hhplus.be.server.layered.infrastructure.persistence.entity.WalletTransactionEntity;
import kr.hhplus.be.server.layered.infrastructure.persistence.repo.SpringWalletJpa;
import kr.hhplus.be.server.layered.infrastructure.persistence.repo.SpringWalletTxJpa;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

@Component
@Transactional
@RequiredArgsConstructor
public class WalletPersistenceAdapter implements WalletReadWritePort {
    private final SpringWalletJpa walletJpa;
    private final SpringWalletTxJpa txJpa;

    @Override
    public Optional<Wallet> lockByUserId(Long userId) {
        return walletJpa.lockByUserId(userId).map(e -> new Wallet(e.getUserId(), e.getBalance()));
    }

    @Override
    public Wallet createZeroBalance(Long userId) {
        var e = new WalletEntity();
        e.setUserId(userId);
        e.setBalance(0L);
        var saved = walletJpa.saveAndFlush(e);
        return new Wallet(saved.getUserId(), saved.getBalance());
    }

    @Override
    public Wallet updateBalance(Long userId, long newBalance) {
        var e = walletJpa.findById(userId)
                .orElseThrow(() -> new IllegalStateException("지갑이 존재하지 않습니다. userId=" + userId));
        e.setBalance(newBalance);
        var saved = walletJpa.saveAndFlush(e);
        return new Wallet(saved.getUserId(), saved.getBalance());
    }

    @Override
    public Optional<WalletTransaction> findTxByIdempotency(Long userId, String idempotencyKey) {
        return txJpa.findByUserIdAndIdempotencyKey(userId, idempotencyKey).map(this::toDomain);
    }

    @Override
    public WalletTransaction saveTopupTx(Long userId, long amount, long balanceAfter, String idempotencyKey, String refType, String refId) {
        var e = new WalletTransactionEntity(
                userId, "TOPUP", amount, balanceAfter,
                refType, refId, idempotencyKey, OffsetDateTime.now()
        );
        var saved = txJpa.saveAndFlush(e);
        return new WalletTransaction(
                saved.getId(), saved.getUserId(), saved.getType(), saved.getAmount(),
                saved.getBalanceAfter(), saved.getRefType(), saved.getRefId(),
                saved.getIdempotencyKey(), saved.getCreatedAt()
        );
    }

    private WalletTransaction toDomain(WalletTransactionEntity e) {
        return new WalletTransaction(
                e.getId(), e.getUserId(), e.getType(), e.getAmount(),
                e.getBalanceAfter(), e.getRefType(), e.getRefId(),
                e.getIdempotencyKey(), e.getCreatedAt()
        );
    }
}
