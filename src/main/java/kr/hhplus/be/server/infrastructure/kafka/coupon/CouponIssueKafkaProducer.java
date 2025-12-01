package kr.hhplus.be.server.infrastructure.kafka.coupon;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 쿠폰 발급 요청 Kafka Producer
 *
 * Topic: coupon.issue.requests
 * Key: couponId (같은 쿠폰은 같은 파티션)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CouponIssueKafkaProducer {

    private final KafkaTemplate<Long, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String TOPIC = "coupon.issue.requests";

    /**
     * 쿠폰 발급 요청 발행
     *
     * @param request 발급 요청
     */
    public void publish(CouponIssueRequest request) {
        try {
            String payload = objectMapper.writeValueAsString(request);

            // Key: couponId (같은 쿠폰은 같은 파티션으로)
            kafkaTemplate.send(TOPIC, request.couponId(), payload);

            log.info("[CouponIssueProducer] 쿠폰 발급 요청 발행: requestId={}, couponId={}, userId={}",
                    request.requestId(), request.couponId(), request.userId());

        } catch (Exception e) {
            log.error("[CouponIssueProducer] 쿠폰 발급 요청 발행 실패: requestId={}, couponId={}, userId={}",
                    request.requestId(), request.couponId(), request.userId(), e);
            throw new IllegalStateException("쿠폰 발급 요청 발행 실패", e);
        }
    }
}
