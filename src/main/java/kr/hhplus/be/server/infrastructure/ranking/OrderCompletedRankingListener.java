package kr.hhplus.be.server.infrastructure.ranking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hhplus.be.server.application.service.ProductRankingUpdater;
import kr.hhplus.be.server.domain.port.out.dto.OrderCompletedEvent;
import kr.hhplus.be.server.infrastructure.outbox.OutboundMessagePublishedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;

/**
 * [DEPRECATED] OrderRankingEventHandler로 대체됨
 *
 * 기존: Outbox → OutboundMessagePublishedEvent → Ranking 업데이트
 * 개선: OrderCompletedDomainEvent → OrderRankingEventHandler → Ranking 업데이트
 *
 * 트랜잭션 커밋 직후 즉시 처리하므로 더 빠르고, 중복 처리 방지
 */
// @Component  // ← 비활성화: 중복 처리 방지
@RequiredArgsConstructor
@Slf4j
public class OrderCompletedRankingListener {

    private final ProductRankingUpdater rankingUpdater;
    private final ObjectMapper objectMapper;

    @Async
    @EventListener
    public void handle(OutboundMessagePublishedEvent message) {
        if (!"ORDER_COMPLETED".equals(message.eventType())) {
            return;
        }

        try {
            OrderCompletedEvent event = objectMapper.readValue(message.payload(), OrderCompletedEvent.class);
            rankingUpdater.handle(event);
        } catch (JsonProcessingException e) {
            log.error("[RankingListener] ORDER_COMPLETED payload 파싱 실패 payload={}", message.payload(), e);
        }
    }
}
