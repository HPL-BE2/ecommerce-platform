package kr.hhplus.be.server.infrastructure.kafka.coupon;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * 쿠폰 발급 결과 Kafka Consumer
 *
 * Topic: coupon.issue.results
 * Consumer Group: coupon-notification
 * Concurrency: 3
 *
 * 처리 흐름:
 * 1. Kafka로부터 발급 결과 수신
 * 2. 사용자에게 알림 발송 (이메일, 푸시 등)
 * 3. 수동 커밋
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CouponIssueResultConsumer {

    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "coupon.issue.results",
            groupId = "coupon-notification",
            concurrency = "3"
    )
    public void consume(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_KEY) Long userId,
            Acknowledgment ack
    ) {
        try {
            // 1. JSON 파싱
            CouponIssueResult result = objectMapper.readValue(payload, CouponIssueResult.class);

            log.info("[CouponResultConsumer] 쿠폰 발급 결과 수신: requestId={}, userId={}, success={}",
                    result.requestId(), result.userId(), result.success());

            // 2. 알림 발송
            if (result.success()) {
                sendSuccessNotification(result);
            } else {
                sendFailureNotification(result);
            }

            // 3. 수동 커밋
            ack.acknowledge();

            log.info("[CouponResultConsumer] 알림 발송 완료: requestId={}, userId={}",
                    result.requestId(), result.userId());

        } catch (Exception e) {
            log.error("[CouponResultConsumer] 알림 발송 실패: userId={}", userId, e);
            // 실패해도 커밋 (알림은 필수가 아니므로)
            ack.acknowledge();
        }
    }

    /**
     * 성공 알림 발송
     *
     * 향후 구현:
     * - 이메일 발송
     * - 푸시 알림
     * - SMS
     */
    private void sendSuccessNotification(CouponIssueResult result) {
        log.info("[CouponNotification] 쿠폰 발급 성공 알림: userId={}, couponId={}, message={}",
                result.userId(), result.couponId(), result.message());

        // TODO: 실제 알림 서비스 연동
        // notificationService.send(result.userId(), "쿠폰이 발급되었습니다!", result.message());
    }

    /**
     * 실패 알림 발송
     *
     * 향후 구현:
     * - 이메일 발송
     * - 푸시 알림
     * - SMS
     */
    private void sendFailureNotification(CouponIssueResult result) {
        log.warn("[CouponNotification] 쿠폰 발급 실패 알림: userId={}, couponId={}, message={}",
                result.userId(), result.couponId(), result.message());

        // TODO: 실제 알림 서비스 연동
        // notificationService.send(result.userId(), "쿠폰 발급 실패", result.message());
    }
}
