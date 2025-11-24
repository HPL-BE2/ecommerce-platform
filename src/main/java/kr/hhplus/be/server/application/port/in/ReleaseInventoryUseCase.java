package kr.hhplus.be.server.application.port.in;

import java.util.List;

/**
 * 재고 해제 UseCase (보상 트랜잭션)
 *
 * Saga 패턴에서 주문 생성 실패 시 이미 예약된 재고를 되돌림
 */
public interface ReleaseInventoryUseCase {

    /**
     * 재고 예약 취소 (보상 트랜잭션)
     *
     * @param command 취소할 재고 정보
     * @return 취소 결과
     */
    Result release(Command command);

    record Command(
            List<Item> items,
            String reason  // 취소 사유 (로깅용)
    ) {
        public record Item(
                Long productId,
                int quantity
        ) {}
    }

    record Result(
            boolean success,
            String message,
            int releasedCount  // 복구된 항목 수
    ) {}
}
