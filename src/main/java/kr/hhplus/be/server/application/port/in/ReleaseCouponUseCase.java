package kr.hhplus.be.server.application.port.in;

/**
 * 쿠폰 해제 UseCase (보상 트랜잭션)
 *
 * Saga 패턴에서 주문 생성 실패 시 이미 예약/사용된 쿠폰을 되돌림
 */
public interface ReleaseCouponUseCase {

    /**
     * 쿠폰 발급 취소 (보상 트랜잭션)
     *
     * @param command 취소할 쿠폰 정보
     * @return 취소 결과
     */
    Result release(Command command);

    record Command(
            Long couponId,
            Long userId,
            String reason  // 취소 사유 (로깅용)
    ) {}

    record Result(
            boolean success,
            String message
    ) {}
}
