package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.application.port.in.RequestCouponIssueUseCase;
import kr.hhplus.be.server.infrastructure.coupon.CouponIssueLuaScriptExecutor;
import kr.hhplus.be.server.infrastructure.kafka.coupon.CouponIssueKafkaProducer;
import kr.hhplus.be.server.infrastructure.kafka.coupon.CouponIssueRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Kafka 기반 비동기 쿠폰 발급 서비스
 *
 * 1. Lua Script로 Redis 검증 (원자적)
 * 2. Kafka로 발급 요청 발행
 * 3. 즉시 응답 (202 Accepted)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AsyncCouponIssueKafkaService implements RequestCouponIssueUseCase {

    private final CouponIssueLuaScriptExecutor luaScriptExecutor;
    private final CouponIssueKafkaProducer kafkaProducer;

    @Override
    public Result request(Command cmd) {
        if (cmd.couponId() == null || cmd.userId() == null) {
            throw new IllegalArgumentException("couponId와 userId는 필수입니다.");
        }

        String requestId = UUID.randomUUID().toString();

        log.info("[AsyncCouponIssueKafka] 쿠폰 발급 요청 시작: requestId={}, couponId={}, userId={}",
                requestId, cmd.couponId(), cmd.userId());

        try {
            // 1. Lua Script로 Redis 검증 (원자적)
            boolean reserved = luaScriptExecutor.execute(
                    cmd.couponId(),
                    cmd.userId()
            );

            if (!reserved) {
                log.warn("[AsyncCouponIssueKafka] 쿠폰 발급 불가 (재고 부족 또는 중복): requestId={}, couponId={}, userId={}",
                        requestId, cmd.couponId(), cmd.userId());
                throw new IllegalStateException("쿠폰 발급에 실패했습니다. 재고가 부족하거나 이미 발급받은 쿠폰입니다.");
            }

            // 2. Kafka로 발급 요청 발행
            CouponIssueRequest request = new CouponIssueRequest(
                    requestId,
                    cmd.couponId(),
                    cmd.userId(),
                    OffsetDateTime.now()
            );

            kafkaProducer.publish(request);

            log.info("[AsyncCouponIssueKafka] 쿠폰 발급 요청 접수: requestId={}, couponId={}, userId={}",
                    requestId, cmd.couponId(), cmd.userId());

            // 3. 즉시 응답
            return new Result(
                    requestId,
                    "쿠폰 발급 요청이 접수되었습니다. 잠시 후 발급 결과를 확인해주세요."
            );

        } catch (Exception e) {
            log.error("[AsyncCouponIssueKafka] 쿠폰 발급 요청 실패: requestId={}, couponId={}, userId={}",
                    requestId, cmd.couponId(), cmd.userId(), e);
            throw e;
        }
    }
}
