package kr.hhplus.be.server.infrastructure.kafka.coupon;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 쿠폰 발급 결과 Kafka Producer
 *
 * Topic: coupon.issue.results
 * Key: userId (같은 사용자는 같은 파티션)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CouponIssueResultProducer {

    private final KafkaTemplate<Long, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String TOPIC = "coupon.issue.results";

    /**
     * 쿠폰 발급 결과 발행
     *
     * @param result 발급 결과
     */
    public void publish(CouponIssueResult result) {
        try {
            String payload = objectMapper.writeValueAsString(result);

            // Key: userId (같은 사용자는 같은 파티션으로)
            kafkaTemplate.send(TOPIC, result.userId(), payload);

            log.info("[CouponIssueResultProducer] 쿠폰 발급 결과 발행: requestId={}, userId={}, success={}",
                    result.requestId(), result.userId(), result.success());

        } catch (Exception e) {
            log.error("[CouponIssueResultProducer] 쿠폰 발급 결과 발행 실패: requestId={}, userId={}",
                    result.requestId(), result.userId(), e);
            throw new IllegalStateException("쿠폰 발급 결과 발행 실패", e);
        }
    }
}
