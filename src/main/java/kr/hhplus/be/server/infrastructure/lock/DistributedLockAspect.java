package kr.hhplus.be.server.infrastructure.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * 분산락 AOP Aspect
 * <p>
 * {@link DistributedLock} 애노테이션이 적용된 메서드에 대해
 * Redisson을 사용한 분산락을 자동으로 적용합니다.
 * </p>
 *
 * <h3>동작 순서</h3>
 * <ol>
 *     <li>SpEL 표현식을 파싱하여 락 키 생성</li>
 *     <li>Redisson RLock 객체 획득</li>
 *     <li>지정된 대기 시간(waitTime) 내에 락 획득 시도</li>
 *     <li>락 획득 성공 시 비즈니스 로직 실행</li>
 *     <li>락 해제 (현재 스레드가 보유한 경우에만)</li>
 * </ol>
 *
 * <h3>Aspect 실행 순서</h3>
 * <p>
 * {@code @Order(Ordered.LOWEST_PRECEDENCE - 1)}로 설정하여
 * {@code @Transactional}(LOWEST_PRECEDENCE)보다 먼저 실행됩니다.
 * <br>
 * 즉, 분산락 획득 → 트랜잭션 시작 → 비즈니스 로직 → 트랜잭션 커밋 → 락 해제 순서로 동작합니다.
 * </p>
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 1)  // @Transactional보다 먼저 실행
@Slf4j
@RequiredArgsConstructor
public class DistributedLockAspect {

    private final RedissonClient redissonClient;
    private final SpelExpressionParser parser = new SpelExpressionParser();

    /**
     * @DistributedLock 애노테이션이 적용된 메서드에 대해 분산락을 적용합니다.
     *
     * @param joinPoint       메서드 실행 정보
     * @param distributedLock 분산락 애노테이션
     * @return 비즈니스 로직 실행 결과
     * @throws Throwable 비즈니스 로직 실행 중 발생한 예외 또는 LockAcquisitionException
     */
    @Around("@annotation(distributedLock)")
    public Object lock(ProceedingJoinPoint joinPoint, DistributedLock distributedLock)
            throws Throwable {

        // 1. SpEL로 락 키 파싱
        String lockKey = parseKey(distributedLock.key(), joinPoint);
        RLock lock = redissonClient.getLock(lockKey);

        log.debug("[DistributedLock] 락 획득 시도: key={}, waitTime={}ms, leaseTime={}ms",
                lockKey, distributedLock.waitTime(), distributedLock.leaseTime());

        // 2. 락 획득 시도
        boolean acquired = false;
        try {
            acquired = lock.tryLock(
                    distributedLock.waitTime(),
                    distributedLock.leaseTime(),
                    TimeUnit.MILLISECONDS
            );

            if (!acquired) {
                log.warn("[DistributedLock] 락 획득 실패: key={}, waitTime={}ms 초과",
                        lockKey, distributedLock.waitTime());
                throw new LockAcquisitionException(distributedLock.failMessage());
            }

            log.debug("[DistributedLock] 락 획득 성공: key={}", lockKey);

            // 3. 비즈니스 로직 수행
            return joinPoint.proceed();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[DistributedLock] 락 획득 중 인터럽트 발생: key={}", lockKey, e);
            throw new LockAcquisitionException("락 획득 중 인터럽트 발생: " + lockKey, e);

        } finally {
            // 4. 락 해제 (현재 스레드가 보유한 경우에만)
            if (acquired && lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                    log.debug("[DistributedLock] 락 해제 완료: key={}", lockKey);
                } catch (IllegalMonitorStateException e) {
                    log.warn("[DistributedLock] 락 해제 실패 (이미 해제됨): key={}", lockKey);
                }
            }
        }
    }

    /**
     * SpEL 표현식을 파싱하여 락 키를 생성합니다.
     * <p>
     * 메서드 파라미터를 SpEL 컨텍스트에 등록하여 동적 키 생성을 지원합니다.
     * </p>
     *
     * @param keyExpression SpEL 표현식 (예: "'coupon:' + #couponId + ':lock'")
     * @param joinPoint     메서드 실행 정보
     * @return 파싱된 락 키
     */
    private String parseKey(String keyExpression, ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // SpEL 컨텍스트 생성
        EvaluationContext context = new StandardEvaluationContext();

        // 메서드 파라미터를 컨텍스트에 등록
        String[] paramNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < paramNames.length; i++) {
            context.setVariable(paramNames[i], args[i]);
        }

        // SpEL 표현식 파싱 및 평가
        try {
            Expression expression = parser.parseExpression(keyExpression);
            String lockKey = expression.getValue(context, String.class);

            if (lockKey == null || lockKey.isEmpty()) {
                throw new IllegalArgumentException("락 키가 비어있습니다: " + keyExpression);
            }

            return lockKey;

        } catch (Exception e) {
            log.error("[DistributedLock] SpEL 파싱 실패: expression={}, method={}",
                    keyExpression, method.getName(), e);
            throw new IllegalArgumentException("SpEL 표현식 파싱 실패: " + keyExpression, e);
        }
    }
}
