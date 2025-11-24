package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.application.port.in.CreateWalletDebitUseCase;
import kr.hhplus.be.server.application.port.in.CreateWalletTopupUseCase;
import kr.hhplus.be.server.application.port.in.RefundWalletUseCase;
import kr.hhplus.be.server.domain.model.Wallet;
import kr.hhplus.be.server.domain.model.WalletTransaction;
import kr.hhplus.be.server.domain.port.out.WalletReadWritePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class WalletService implements CreateWalletTopupUseCase, CreateWalletDebitUseCase, RefundWalletUseCase {
    private final WalletReadWritePort rwPort;

    @Override
    @Transactional
    public CreateWalletTopupUseCase.Result topup(CreateWalletTopupUseCase.Command cmd) {
        if (cmd.userId() == null || cmd.amount() == null || cmd.amount() <= 0)
            throw new IllegalArgumentException("userId/amount는 필수이며 amount>0 이어야 합니다.");
        if (cmd.idempotencyKey() == null || cmd.idempotencyKey().isBlank())
            throw new IllegalArgumentException("idempotencyKey는 필수입니다.");

        // 1) 멱등키 우선 조회
        var existing = rwPort.findTxByIdempotency(cmd.userId(), cmd.idempotencyKey());
        if (existing.isPresent()) {
            WalletTransaction tx = existing.get();
            return new CreateWalletTopupUseCase.Result(tx.id(), tx.balanceAfter(), true);
        }

        // 2) 지갑 행 잠금 or 생성
        Wallet wallet = rwPort.lockByUserId(cmd.userId()).orElse(null);
        if (wallet == null) {
            try {
                rwPort.createZeroBalance(cmd.userId()); // FK 위반이면 여기서 터짐 → 404로 변환
            } catch (DataIntegrityViolationException dup) {
                // 다른 트랜잭션이 먼저 만들었다면 무시하고 다음 단계로
            }
            wallet = rwPort.lockByUserId(cmd.userId())
                    .orElseThrow(() -> new IllegalStateException("지갑 생성/잠금 실패: userId=" + cmd.userId()));
        }

        long newBalance = Math.addExact(wallet.balance(), cmd.amount()); // overflow 방지

        // 3) 트랜잭션 기록
        var tx = rwPort.saveTopupTx(
                cmd.userId(), cmd.amount(), newBalance,
                cmd.idempotencyKey(), cmd.refType(), cmd.refId()
        );

        // 4) 지갑 잔액 반영
        rwPort.updateBalance(cmd.userId(), newBalance);

        return new CreateWalletTopupUseCase.Result(tx.id(), newBalance, false);
    }

    @Override
    @Transactional
    public CreateWalletDebitUseCase.Result debit(CreateWalletDebitUseCase.Command cmd) {
        if (cmd.userId() == null || cmd.amount() == null || cmd.amount() <= 0)
            throw new IllegalArgumentException("userId/amount는 필수이며 amount>0 이어야 합니다.");
        if (cmd.idempotencyKey() == null || cmd.idempotencyKey().isBlank())
            throw new IllegalArgumentException("idempotencyKey는 필수입니다.");

        // 1) 멱등키 우선 조회 (중복 차감 방지)
        var existing = rwPort.findTxByIdempotency(cmd.userId(), cmd.idempotencyKey());
        if (existing.isPresent()) {
            WalletTransaction tx = existing.get();
            return new CreateWalletDebitUseCase.Result(tx.id(), tx.balanceAfter(), true);
        }

        // 2) 지갑 행 잠금 (Pessimistic Lock - 동시성 제어)
        Wallet wallet = rwPort.lockByUserId(cmd.userId())
                .orElseThrow(() -> new IllegalArgumentException("지갑을 찾을 수 없습니다: userId=" + cmd.userId()));

        // 3) 잔액 검증
        if (wallet.balance() < cmd.amount()) {
            throw new IllegalStateException(
                    "잔액 부족: userId=" + cmd.userId() +
                    ", 현재잔액=" + wallet.balance() +
                    ", 요청금액=" + cmd.amount()
            );
        }

        // 4) 잔액 차감
        long newBalance = wallet.balance() - cmd.amount();

        // 5) 트랜잭션 기록
        var tx = rwPort.saveDebitTx(
                cmd.userId(), cmd.amount(), newBalance,
                cmd.idempotencyKey(), cmd.refType(), cmd.refId()
        );

        // 6) 지갑 잔액 반영
        rwPort.updateBalance(cmd.userId(), newBalance);

        return new CreateWalletDebitUseCase.Result(tx.id(), newBalance, false);
    }

    /**
     * 지갑 환불 (보상 트랜잭션)
     *
     * Saga 패턴에서 주문 생성 실패 시 이미 차감된 금액을 되돌림
     * 멱등성: "REFUND:" + originalIdempotencyKey로 중복 환불 방지
     */
    @Override
    @Transactional
    public RefundWalletUseCase.Result refund(RefundWalletUseCase.Command cmd) {
        if (cmd.userId() == null || cmd.amount() == null || cmd.amount() <= 0) {
            throw new IllegalArgumentException("userId/amount는 필수이며 amount>0 이어야 합니다.");
        }
        if (cmd.originalIdempotencyKey() == null || cmd.originalIdempotencyKey().isBlank()) {
            throw new IllegalArgumentException("originalIdempotencyKey는 필수입니다.");
        }

        log.info("[WalletService] 환불 시작: userId={}, amount={}, originalKey={}, reason={}",
                cmd.userId(), cmd.amount(), cmd.originalIdempotencyKey(), cmd.reason());

        try {
            // 1) 멱등키: "REFUND:" + 원본키 (중복 환불 방지)
            String refundIdempotencyKey = "REFUND:" + cmd.originalIdempotencyKey();

            // 멱등키 우선 조회
            var existing = rwPort.findTxByIdempotency(cmd.userId(), refundIdempotencyKey);
            if (existing.isPresent()) {
                WalletTransaction tx = existing.get();
                log.info("[WalletService] 환불 이미 처리됨 (멱등): userId={}, txId={}", cmd.userId(), tx.id());
                return new RefundWalletUseCase.Result(tx.id(), tx.balanceAfter(), true, "이미 환불되었습니다.");
            }

            // 2) 지갑 행 잠금
            Wallet wallet = rwPort.lockByUserId(cmd.userId())
                    .orElseThrow(() -> new IllegalArgumentException("지갑을 찾을 수 없습니다: userId=" + cmd.userId()));

            // 3) 잔액 증가
            long newBalance = Math.addExact(wallet.balance(), cmd.amount()); // overflow 방지

            // 4) 트랜잭션 기록 (topup으로 기록)
            var tx = rwPort.saveTopupTx(
                    cmd.userId(), cmd.amount(), newBalance,
                    refundIdempotencyKey,
                    "REFUND",  // refType: 환불
                    cmd.reason()  // refId: 환불 사유
            );

            // 5) 지갑 잔액 반영
            rwPort.updateBalance(cmd.userId(), newBalance);

            log.info("[WalletService] 환불 완료: userId={}, amount={}, newBalance={}, txId={}",
                    cmd.userId(), cmd.amount(), newBalance, tx.id());

            return new RefundWalletUseCase.Result(tx.id(), newBalance, true, "환불이 완료되었습니다.");

        } catch (Exception e) {
            log.error("[WalletService] 환불 실패: userId={}, amount={}, error={}",
                    cmd.userId(), cmd.amount(), e.getMessage(), e);
            return new RefundWalletUseCase.Result(null, null, false, "환불 실패: " + e.getMessage());
        }
    }
}
