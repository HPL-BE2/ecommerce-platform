package kr.hhplus.be.server.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Analytics Kafka Consumer
 *
 * 주문 완료 이벤트를 수신하여 재고 분석 및 트렌드 분석
 * Consumer Group: analytics
 * Concurrency: 2
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AnalyticsKafkaConsumer {

    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "ecommerce.order.events",
            groupId = "analytics",
            concurrency = "2"
    )
    public void consume(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_KEY) Long orderId,
            Acknowledgment ack
    ) {
        log.info("[Analytics] 메시지 수신: orderId={}", orderId);

        try {
            // JSON 파싱
            OrderCompletedEvent event = objectMapper.readValue(payload, OrderCompletedEvent.class);

            // 재고 분석 (향후 구현)
            // analyticsService.analyzeInventory(event);

            // 판매 트렌드 분석 (향후 구현)
            // analyticsService.analyzeSalesTrend(event);

            log.info("[Analytics] 분석 시뮬레이션 완료: orderId={}, items={}",
                    orderId, event.items().size());

            // 처리 완료 후 커밋
            ack.acknowledge();

            log.info("[Analytics] 분석 완료: orderId={}", orderId);

        } catch (Exception e) {
            log.error("[Analytics] 분석 실패: orderId={}", orderId, e);
        }
    }
}
