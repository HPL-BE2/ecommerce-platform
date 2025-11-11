package kr.hhplus.be.server.infrastructure.ranking;

import kr.hhplus.be.server.domain.model.ProductRankingEntry;
import kr.hhplus.be.server.domain.port.out.ProductRankingPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class RedisProductRankingAdapter implements ProductRankingPort {

    private final RedisTemplate<String, String> counterRedisTemplate;

    @Override
    public void incrementScores(String rankingKey, Map<Long, Integer> qtyByProductId) {
        if (qtyByProductId == null || qtyByProductId.isEmpty()) {
            return;
        }

        // 파이프라인으로 ZINCRBY batch 처리
        try {
            counterRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                qtyByProductId.forEach((productId, qty) -> {
                    if (productId == null || qty == null || qty == 0) return;
                    byte[] keyBytes = rankingKey.getBytes(StandardCharsets.UTF_8);
                    byte[] memberBytes = productId.toString().getBytes(StandardCharsets.UTF_8);
                    connection.zIncrBy(keyBytes, qty.doubleValue(), memberBytes);
                });
                return null;
            });
        } catch (DataAccessException ex) {
            log.error("[Ranking] ZINCRBY 실패 key={} size={}", rankingKey, qtyByProductId.size(), ex);
            throw ex;
        }
    }

    @Override
    public List<ProductRankingEntry> fetchTop(String rankingKey, int limit) {
        if (limit <= 0) {
            return List.of();
        }

        ZSetOperations<String, String> zset = counterRedisTemplate.opsForZSet();
        Set<ZSetOperations.TypedTuple<String>> tuples = zset.reverseRangeWithScores(rankingKey, 0, limit - 1);
        if (CollectionUtils.isEmpty(tuples)) {
            return List.of();
        }

        List<ProductRankingEntry> result = new ArrayList<>(tuples.size());
        for (var tuple : tuples) {
            if (tuple == null || tuple.getValue() == null || tuple.getScore() == null) continue;
            Long productId = parseLong(tuple.getValue());
            if (productId == null) continue;
            long score = tuple.getScore().longValue();
            result.add(new ProductRankingEntry(productId, score));
        }
        return result;
    }

    private Long parseLong(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            log.warn("[Ranking] 잘못된 productId value={} - skip", value);
            return null;
        }
    }
}
