package kr.hhplus.be.server.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hhplus.be.server.application.service.ProductRankingUpdater;
import kr.hhplus.be.server.domain.port.out.dto.OrderCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Ranking Kafka Consumer
 *
 * 주문 완료 이벤트를 수신하여 Redis 랭킹 업데이트
 * Consumer Group: ranking-updater
 * Concurrency: 3
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RankingKafkaConsumer {

    private final ProductRankingUpdater rankingUpdater;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "ecommerce.order.events",
            groupId = "ranking-updater",
            concurrency = "3"
    )
    public void consume(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_KEY) Long orderId,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment ack
    ) {
        log.info("[Ranking] 메시지 수신: orderId={}, partition={}, offset={}",
                orderId, partition, offset);

        try {
            // JSON 파싱
            OrderCompletedEvent event = objectMapper.readValue(payload, OrderCompletedEvent.class);

            // 랭킹 업데이트
            rankingUpdater.handle(event);

            // 처리 완료 후 커밋
            ack.acknowledge();

            log.info("[Ranking] 랭킹 업데이트 완료: orderId={}, items={}",
                    orderId, event.items().size());

        } catch (Exception e) {
            log.error("[Ranking] 처리 실패: orderId={}, partition={}, offset={}",
                    orderId, partition, offset, e);
            // 커밋하지 않음 → 재처리
        }
    }
}
