package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.application.port.in.GetProductRankingUseCase;
import kr.hhplus.be.server.domain.model.Product;
import kr.hhplus.be.server.domain.model.ProductRankingEntry;
import kr.hhplus.be.server.domain.model.RankingPeriod;
import kr.hhplus.be.server.domain.port.out.ProductBulkReadPort;
import kr.hhplus.be.server.domain.port.out.ProductRankingPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataAccessException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class ProductRankingService implements GetProductRankingUseCase {
    private final ProductRankingPort rankingPort;
    private final ProductBulkReadPort productBulkReadPort;
    private final RankingKeyResolver rankingKeyResolver;

    @Override
    public Result getTopProducts(Query query) {
        RankingPeriod period = query.period() != null ? query.period() : RankingPeriod.REALTIME;
        int limit = normalizeLimit(query.limit());
        String rankingKey = rankingKeyResolver.resolveKey(period, query.referenceDate());

        List<ProductRankingEntry> entries;
        try {
            entries = rankingPort.fetchTop(rankingKey, limit);
        } catch (DataAccessException ex) {
            log.error("[RankingService] Redis 조회 실패 key={} - fallback empty result", rankingKey, ex);
            return new Result(List.of(), rankingKey);
        }
        if (entries.isEmpty()) {
            return new Result(List.of(), rankingKey);
        }

        Map<Long, Product> productById = productBulkReadPort.findByIds(
                entries.stream().map(ProductRankingEntry::productId).collect(Collectors.toSet())
        );

        List<Item> items = entries.stream()
                .map(entry -> {
                    Product product = productById.get(entry.productId());
                    if (product == null) return null;
                    return new Item(
                            product.id(),
                            product.name(),
                            product.thumbnailUrl(),
                            product.price() != null ? product.price().intValue() : 0,
                            entry.score()
                    );
                })
                .filter(Objects::nonNull)
                .toList();

        return new Result(items, rankingKey);
    }

    private int normalizeLimit(Integer raw) {
        int limit = raw != null ? raw : 10;
        return Math.max(1, Math.min(50, limit));
    }
}
