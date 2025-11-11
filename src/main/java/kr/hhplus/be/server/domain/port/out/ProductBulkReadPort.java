package kr.hhplus.be.server.domain.port.out;

import kr.hhplus.be.server.domain.model.Product;

import java.util.Collection;
import java.util.Map;

/**
 * 다건 상품 조회용 포트. 랭킹/추천 등에서 상위 상품 정보를 한번에 가져온다.
 */
public interface ProductBulkReadPort {
    Map<Long, Product> findByIds(Collection<Long> productIds);
}
