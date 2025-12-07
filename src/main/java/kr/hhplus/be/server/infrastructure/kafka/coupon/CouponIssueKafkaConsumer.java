package kr.hhplus.be.server.infrastructure.kafka.coupon;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hhplus.be.server.application.port.in.IssueCouponUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 쿠폰 발급 요청 Kafka Consumer
 *
 * Topic: coupon.issue.requests
 * Consumer Group: coupon-issue-processor
 * Concurrency: 5 (병렬 처리)
 *
 * 처리 흐름:
 * 1. Kafka로부터 발급 요청 수신
 * 2. DB에 쿠폰 발급 처리
 * 3. 결과를 coupon.issue.results 토픽으로 발행
 * 4. 수동 커밋
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CouponIssueKafkaConsumer {

    private final ObjectMapper objectMapper;
    private final IssueCouponUseCase issueCouponUseCase;
    private final CouponIssueResultProducer resultProducer;

    @KafkaListener(
            topics = "coupon.issue.requests",
            groupId = "coupon-issue-processor",
            concurrency = "5"
    )
    public void consume(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_KEY) Long couponId,
            Acknowledgment ack
    ) {
        CouponIssueRequest request = null;

        try {
            // 1. JSON 파싱
            request = objectMapper.readValue(payload, CouponIssueRequest.class);

            log.info("[CouponIssueConsumer] 쿠폰 발급 요청 수신: requestId={}, couponId={}, userId={}",
                    request.requestId(), request.couponId(), request.userId());

            // 2. 쿠폰 발급 처리
            IssueCouponUseCase.Result issueResult = issueCouponUseCase.issue(
                    new IssueCouponUseCase.Command(request.couponId(), request.userId())
            );

            // 3. 성공 결과 발행
            CouponIssueResult result = new CouponIssueResult(
                    request.requestId(),
                    request.couponId(),
                    request.userId(),
                    true,
                    "쿠폰이 성공적으로 발급되었습니다.",
                    OffsetDateTime.now()
            );
            resultProducer.publish(result);

            // 4. 수동 커밋
            ack.acknowledge();

            log.info("[CouponIssueConsumer] 쿠폰 발급 완료: requestId={}, couponId={}, userId={}",
                    request.requestId(), request.couponId(), request.userId());

        } catch (Exception e) {
            log.error("[CouponIssueConsumer] 쿠폰 발급 실패: requestId={}, couponId={}, userId={}",
                    request != null ? request.requestId() : "unknown",
                    couponId,
                    request != null ? request.userId() : "unknown",
                    e);

            // 실패 결과 발행 (request가 파싱된 경우에만)
            if (request != null) {
                try {
                    CouponIssueResult result = new CouponIssueResult(
                            request.requestId(),
                            request.couponId(),
                            request.userId(),
                            false,
                            "쿠폰 발급에 실패했습니다: " + e.getMessage(),
                            OffsetDateTime.now()
                    );
                    resultProducer.publish(result);

                    // 실패해도 커밋 (DLQ로 이동시키지 않음)
                    ack.acknowledge();
                } catch (Exception publishError) {
                    log.error("[CouponIssueConsumer] 실패 결과 발행 중 오류: requestId={}",
                            request.requestId(), publishError);
                }
            }
        }
    }
}
