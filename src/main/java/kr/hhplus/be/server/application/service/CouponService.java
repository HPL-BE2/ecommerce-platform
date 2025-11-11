package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.application.port.in.IssueCouponUseCase;
import kr.hhplus.be.server.domain.model.Coupon;
import kr.hhplus.be.server.domain.port.out.CouponReadWritePort;
import kr.hhplus.be.server.infrastructure.lock.DistributedLock;
import kr.hhplus.be.server.infrastructure.persistence.adapter.CouponPersistenceAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class CouponService implements IssueCouponUseCase {
    private final CouponReadWritePort couponPort;
    private final CouponPersistenceAdapter couponAdapter;
    private final RedisTemplate<String, String> counterRedisTemplate;  // 카운터 전용 (INCR/DECR)

    @Override
    @DistributedLock(
            key = "'coupon:' + #cmd.couponId() + ':lock'",
            leaseTime = 5000,
            waitTime = 3000,
            failMessage = "쿠폰 발급 요청이 집중되어 처리할 수 없습니다. 잠시 후 다시 시도해주세요."
    )
    @Transactional
    public Result issue(Command cmd) {
        if (cmd.couponId() == null || cmd.userId() == null) {
            throw new IllegalArgumentException("couponId와 userId는 필수입니다.");
        }

        // 1) 쿠폰 조회
        Coupon coupon = couponPort.findById(cmd.couponId())
                .orElseThrow(() -> new IllegalArgumentException("쿠폰을 찾을 수 없습니다: couponId=" + cmd.couponId()));

        // 2) 쿠폰 유효성 검증 (기간)
        if (!coupon.isActive(OffsetDateTime.now())) {
            throw new IllegalStateException("쿠폰 발급 기간이 아닙니다: couponId=" + cmd.couponId());
        }

        // 3) 중복 발급 확인 (빠른 실패)
        if (couponPort.isAlreadyIssued(cmd.couponId(), cmd.userId())) {
            throw new IllegalStateException("이미 발급받은 쿠폰입니다: couponId=" + cmd.couponId() + ", userId=" + cmd.userId());
        }

        // 4) Redis Atomic Counter로 발급 수량 제한 확인 (성능 개선)
        if (coupon.hasIssuanceLimit()) {
            String countKey = "coupon:" + cmd.couponId() + ":issued";

            // Redis Atomic Increment (분산 환경에서 원자적 증가)
            Long currentCount = counterRedisTemplate.opsForValue().increment(countKey);

            log.debug("[CouponService] 쿠폰 발급 시도: couponId={}, currentCount={}, maxIssuance={}",
                    cmd.couponId(), currentCount, coupon.maxIssuance());

            // 최대 발급량 초과 체크
            if (currentCount > coupon.maxIssuance()) {
                // 초과된 카운트 롤백 (예외 처리 강화)
                try {
                    counterRedisTemplate.opsForValue().decrement(countKey);
                    log.debug("[CouponService] Redis 카운터 롤백 성공: couponId={}, countKey={}",
                            cmd.couponId(), countKey);
                } catch (RedisConnectionFailureException e) {
                    // 롤백 실패 시 로그 + 알림 (추후 배치 동기화)
                    log.error("[CouponService] Redis 카운터 롤백 실패 (네트워크 장애): couponId={}, countKey={}, " +
                            "조치: 배치 동기화 필요", cmd.couponId(), countKey, e);
                    // TODO: 알림 발송 or 동기화 큐 적재
                } catch (Exception e) {
                    log.error("[CouponService] Redis 카운터 롤백 실패 (예상치 못한 오류): couponId={}, countKey={}",
                            cmd.couponId(), countKey, e);
                }

                log.warn("[CouponService] 쿠폰 소진: couponId={}, attemptedCount={}, maxIssuance={}",
                        cmd.couponId(), currentCount, coupon.maxIssuance());
                throw new IllegalStateException("쿠폰이 모두 소진되었습니다: couponId=" + cmd.couponId());
            }
        }

        // 5) 쿠폰 발급 (DB에 기록)
        Long issuanceId;
        try {
            issuanceId = couponPort.issueCoupon(cmd.couponId(), cmd.userId());
            log.info("[CouponService] 쿠폰 발급 성공: issuanceId={}, couponId={}, userId={}",
                    issuanceId, cmd.couponId(), cmd.userId());

        } catch (DataIntegrityViolationException e) {
            // UNIQUE 제약 위반 (동시 요청으로 인한 중복 발급 시도)
            // Redis 카운터 롤백 (예외 처리 강화)
            if (coupon.hasIssuanceLimit()) {
                String countKey = "coupon:" + cmd.couponId() + ":issued";
                try {
                    counterRedisTemplate.opsForValue().decrement(countKey);
                    log.debug("[CouponService] Redis 카운터 롤백 성공 (중복 발급): couponId={}, userId={}",
                            cmd.couponId(), cmd.userId());
                } catch (RedisConnectionFailureException rollbackEx) {
                    // 롤백 실패 시 로그 + 알림 (추후 배치 동기화)
                    log.error("[CouponService] Redis 카운터 롤백 실패 (네트워크 장애): couponId={}, userId={}, " +
                            "조치: 배치 동기화 필요", cmd.couponId(), cmd.userId(), rollbackEx);
                    // TODO: 알림 발송 or 동기화 큐 적재
                } catch (Exception rollbackEx) {
                    log.error("[CouponService] Redis 카운터 롤백 실패 (예상치 못한 오류): couponId={}, userId={}",
                            cmd.couponId(), cmd.userId(), rollbackEx);
                }

                log.warn("[CouponService] 중복 발급 시도로 인한 롤백: couponId={}, userId={}",
                        cmd.couponId(), cmd.userId());
            }
            throw new IllegalStateException("이미 발급받은 쿠폰입니다: couponId=" + cmd.couponId() + ", userId=" + cmd.userId());
        }

        return new Result(issuanceId, cmd.couponId(), cmd.userId(), "쿠폰이 발급되었습니다.");
    }
}
