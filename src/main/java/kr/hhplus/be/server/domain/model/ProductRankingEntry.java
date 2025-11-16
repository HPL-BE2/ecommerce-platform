package kr.hhplus.be.server.domain.model;

/**
 * Redis Sorted Set에서 조회한 상품 랭킹 정보 (productId + 누적 주문 수).
 */
public record ProductRankingEntry(Long productId, long score) {
}
