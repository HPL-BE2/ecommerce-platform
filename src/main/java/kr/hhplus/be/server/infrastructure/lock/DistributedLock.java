package kr.hhplus.be.server.infrastructure.lock;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 분산락 애노테이션
 * <p>
 * Redisson을 사용하여 분산 환경에서 동시성을 제어합니다.
 * SpEL(Spring Expression Language)을 지원하여 메서드 파라미터를 기반으로 동적 키 생성이 가능합니다.
 * </p>
 *
 * <h3>사용 예시</h3>
 * <pre>{@code
 * @DistributedLock(
 *     key = "'coupon:' + #couponId + ':lock'",
 *     leaseTime = 5000,
 *     waitTime = 3000
 * )
 * public void issueCoupon(Long couponId, Long userId) {
 *     // 쿠폰 발급 로직
 * }
 * }</pre>
 *
 * <h3>주의사항</h3>
 * <ul>
 *     <li>leaseTime은 DB 트랜잭션 타임아웃보다 길게 설정해야 합니다.</li>
 *     <li>분산락 획득 후 DB 트랜잭션을 시작해야 데드락을 방지할 수 있습니다.</li>
 *     <li>락 획득 실패 시 LockAcquisitionException이 발생합니다.</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {

    /**
     * 락 키 (SpEL 표현식 지원)
     * <p>
     * 예시:
     * <ul>
     *     <li>"'coupon:' + #couponId + ':lock'"</li>
     *     <li>"'product:' + #productId + ':order:lock'"</li>
     *     <li>"'payment:' + #userId + ':' + #idempotencyKey"</li>
     * </ul>
     * </p>
     */
    String key();

    /**
     * 락 점유 시간 (milliseconds)
     * <p>
     * 기본값: 5000ms (5초)
     * </p>
     * <p>
     * 이 시간이 지나면 자동으로 락이 해제됩니다. (TTL)
     * DB 트랜잭션 타임아웃보다 길게 설정해야 합니다.
     * </p>
     */
    long leaseTime() default 5000L;

    /**
     * 락 대기 시간 (milliseconds)
     * <p>
     * 기본값: 3000ms (3초)
     * </p>
     * <p>
     * 이 시간 내에 락을 획득하지 못하면 LockAcquisitionException이 발생합니다.
     * </p>
     */
    long waitTime() default 3000L;

    /**
     * 락 획득 실패 시 예외 메시지
     */
    String failMessage() default "리소스 잠금 획득 실패";
}
