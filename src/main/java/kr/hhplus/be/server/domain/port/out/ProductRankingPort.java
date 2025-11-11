package kr.hhplus.be.server.domain.port.out;

import kr.hhplus.be.server.domain.model.ProductRankingEntry;

import java.util.List;
import java.util.Map;

/**
 * Redis Sorted Set 기반 상품 랭킹 저장소.
 */
public interface ProductRankingPort {

    /**
     * 상품별 주문량을 증가(또는 감소)시킨다. qty에 음수를 주면 차감.
     */
    void incrementScores(String rankingKey, Map<Long, Integer> qtyByProductId);

    /**
     * 랭킹 상위 N개 상품을 점수 내림차순으로 조회한다.
     */
    List<ProductRankingEntry> fetchTop(String rankingKey, int limit);
}
