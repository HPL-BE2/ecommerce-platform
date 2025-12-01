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
 * Data Platform Kafka Consumer
 *
 * 주문 완료 이벤트를 수신하여 외부 데이터 플랫폼으로 전송
 * Consumer Group: data-platform
 * Concurrency: 1 (순차 처리)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataPlatformKafkaConsumer {

    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "ecommerce.order.events",
            groupId = "data-platform",
            concurrency = "1"
    )
    public void consume(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_KEY) Long orderId,
            Acknowledgment ack
    ) {
        log.info("[DataPlatform] 메시지 수신: orderId={}", orderId);

        try {
            // JSON 파싱
            OrderCompletedEvent event = objectMapper.readValue(payload, OrderCompletedEvent.class);

            // 외부 데이터 플랫폼으로 전송 (향후 구현)
            // dataPlatformClient.send(event);
            log.info("[DataPlatform] 데이터 플랫폼 전송 시뮬레이션: orderId={}", orderId);

            // 처리 완료 후 커밋
            ack.acknowledge();

            log.info("[DataPlatform] 전송 완료: orderId={}", orderId);

        } catch (Exception e) {
            log.error("[DataPlatform] 전송 실패: orderId={}", orderId, e);
            // 실패 시 DLQ(Dead Letter Queue)로 이동 또는 알람
        }
    }
}
