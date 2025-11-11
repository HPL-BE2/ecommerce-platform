package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.domain.model.RankingPeriod;
import kr.hhplus.be.server.domain.port.out.ProductRankingPort;
import kr.hhplus.be.server.domain.port.out.dto.OrderCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProductRankingUpdater {
    private static final EnumSet<RankingPeriod> PERIODS = EnumSet.of(
            RankingPeriod.REALTIME,
            RankingPeriod.DAILY,
            RankingPeriod.WEEKLY,
            RankingPeriod.MONTHLY
    );

    private final ProductRankingPort rankingPort;
    private final RankingKeyResolver rankingKeyResolver;

    public void handle(OrderCompletedEvent event) {
        if (event == null || event.items() == null || event.items().isEmpty()) {
            return;
        }

        Map<Long, Integer> qtyMap = event.items().stream()
                .collect(Collectors.toMap(
                        OrderCompletedEvent.Item::productId,
                        OrderCompletedEvent.Item::qty,
                        Integer::sum
                ));

        PERIODS.forEach(period -> {
            String key = rankingKeyResolver.resolveKey(period, event.completedAt());
            rankingPort.incrementScores(key, qtyMap);
        });

        log.debug("[RankingUpdater] 주문 이벤트 적용 orderId={} qtyMap={}", event.orderId(), qtyMap);
    }
}
