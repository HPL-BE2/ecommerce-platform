package kr.hhplus.be.server.application.port.in;

import kr.hhplus.be.server.domain.model.RankingPeriod;

import java.time.LocalDate;
import java.util.List;

public interface GetProductRankingUseCase {

    record Query(RankingPeriod period, Integer limit, LocalDate referenceDate) { }

    record Item(Long productId, String name, String thumbnailUrl, int unitPrice, long score) { }

    record Result(List<Item> items, String rankingKey) { }

    Result getTopProducts(Query query);
}
