package kr.hhplus.be.server.domain.port.out;

import kr.hhplus.be.server.domain.model.Wallet;
import kr.hhplus.be.server.domain.model.WalletTransaction;

import java.util.Optional;

/**
 * Wallet 도메인의 영속성 포트
 */
public interface WalletReadWritePort {
    /**
     * 지갑 잠금 (Pessimistic Lock)
     * @param userId 사용자 ID
     * @return 지갑 정보
     */
    Optional<Wallet> lockByUserId(Long userId);

    /**
     * 잔액 0으로 지갑 생성
     * @param userId 사용자 ID
     * @return 생성된 지갑
     */
    Wallet createZeroBalance(Long userId);

    /**
     * 잔액 업데이트
     * @param userId 사용자 ID
     * @param newBalance 새 잔액
     * @return 업데이트된 지갑
     */
    Wallet updateBalance(Long userId, long newBalance);

    /**
     * Idempotency Key로 트랜잭션 조회
     * @param userId 사용자 ID
     * @param idempotencyKey 멱등키
     * @return 트랜잭션
     */
    Optional<WalletTransaction> findTxByIdempotency(Long userId, String idempotencyKey);

    /**
     * 충전 트랜잭션 저장
     * @param userId 사용자 ID
     * @param amount 충전 금액
     * @param balanceAfter 충전 후 잔액
     * @param idempotencyKey 멱등키
     * @param refType 참조 타입
     * @param refId 참조 ID
     * @return 저장된 트랜잭션
     */
    WalletTransaction saveTopupTx(Long userId, long amount, long balanceAfter, String idempotencyKey, String refType, String refId);

    /**
     * 차감 트랜잭션 저장
     * @param userId 사용자 ID
     * @param amount 차감 금액
     * @param balanceAfter 차감 후 잔액
     * @param idempotencyKey 멱등키
     * @param refType 참조 타입 (예: "ORDER")
     * @param refId 참조 ID (예: 주문번호)
     * @return 저장된 트랜잭션
     */
    WalletTransaction saveDebitTx(Long userId, long amount, long balanceAfter, String idempotencyKey, String refType, String refId);
}
