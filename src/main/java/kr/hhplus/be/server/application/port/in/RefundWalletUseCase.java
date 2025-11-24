package kr.hhplus.be.server.application.port.in;

/**
 * 지갑 환불 UseCase (보상 트랜잭션)
 *
 * Saga 패턴에서 주문 생성 실패 시 이미 차감된 금액을 되돌림
 */
public interface RefundWalletUseCase {

    /**
     * 지갑 환불 (보상 트랜잭션)
     *
     * @param command 환불 정보
     * @return 환불 결과
     */
    Result refund(Command command);

    record Command(
            Long userId,
            Long amount,
            String originalIdempotencyKey,  // 원본 차감 거래의 멱등키
            String reason  // 환불 사유
    ) {}

    record Result(
            Long transactionId,
            Long balanceAfter,
            boolean success,
            String message
    ) {}
}
