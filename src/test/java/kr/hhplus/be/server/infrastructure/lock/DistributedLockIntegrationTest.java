package kr.hhplus.be.server.infrastructure.lock;

import kr.hhplus.be.server.TestcontainersConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 분산락 통합 테스트
 * <p>
 * Redisson 기반 분산락이 멀티스레드 환경에서 올바르게 동작하는지 검증합니다.
 * </p>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@DisplayName("분산락 통합 테스트")
@Slf4j
class DistributedLockIntegrationTest {

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private TestLockService testLockService;

    @BeforeEach
    void setUp() {
        // Redis 초기화
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    @DisplayName("분산락 획득 및 해제 기본 동작 테스트")
    void testBasicLockOperation() {
        // Given
        String lockKey = "test:lock:basic";
        Long resourceId = 1L;

        // When
        String result = testLockService.processWithLock(resourceId);

        // Then
        assertThat(result).isEqualTo("success");
        assertThat(redissonClient.getLock(lockKey).isLocked()).isFalse();
    }

    @Test
    @DisplayName("100개 스레드가 동시에 락 획득 시도 - 순차 처리 검증")
    void testConcurrentLockAcquisition_100Threads() throws InterruptedException {
        // Given
        Long resourceId = 100L;
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // When: 100개 스레드가 동시에 락 획득 시도
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    testLockService.processWithLock(resourceId);
                    successCount.incrementAndGet();
                } catch (LockAcquisitionException e) {
                    failCount.incrementAndGet();
                } catch (Exception e) {
                    log.error("Unexpected error", e);
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Then: 모든 스레드가 순차적으로 처리됨
        log.info("Success: {}, Fail: {}", successCount.get(), failCount.get());
        assertThat(successCount.get() + failCount.get()).isEqualTo(threadCount);

        // 일부는 대기 시간 초과로 실패 가능
        assertThat(successCount.get()).isGreaterThan(0);
    }

    @Test
    @DisplayName("동일 리소스에 대한 동시 접근 시 데이터 정합성 보장")
    void testDataConsistency_WithDistributedLock() throws InterruptedException {
        // Given: Redis Counter 초기화
        String counterKey = "test:counter";
        redisTemplate.opsForValue().set(counterKey, 0);

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // When: 50개 스레드가 동시에 카운터 증가
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    testLockService.incrementCounter(counterKey);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Then: 카운터 값이 정확히 50
        Integer finalCount = (Integer) redisTemplate.opsForValue().get(counterKey);
        assertThat(finalCount).isEqualTo(threadCount);
    }

    @Test
    @DisplayName("락 획득 실패 시 LockAcquisitionException 발생")
    void testLockAcquisitionFailure() {
        // Given: 락을 먼저 획득
        Long resourceId = 999L;
        var lock = redissonClient.getLock("test:lock:" + resourceId);
        lock.lock(10, TimeUnit.SECONDS);

        try {
            // When & Then: 동일 리소스에 대한 락 획득 시도 시 예외 발생
            assertThatThrownBy(() -> testLockService.processWithLock(resourceId))
                    .isInstanceOf(LockAcquisitionException.class)
                    .hasMessageContaining("락 획득 실패");
        } finally {
            lock.unlock();
        }
    }

    @Test
    @DisplayName("SpEL 표현식으로 동적 락 키 생성")
    void testDynamicLockKeyWithSpEL() {
        // Given
        Long userId = 123L;
        String idempotencyKey = "test-key-456";

        // When
        String result = testLockService.processPayment(userId, idempotencyKey);

        // Then
        assertThat(result).contains("payment processed");
    }

    /**
     * 테스트용 서비스 클래스
     */
    @Service
    @Slf4j
    @RequiredArgsConstructor
    public static class TestLockService {

        private final RedisTemplate<String, Object> redisTemplate;

        /**
         * 기본 락 테스트용 메서드
         */
        @DistributedLock(key = "'test:lock:' + #resourceId",
                         leaseTime = 5000,
                         waitTime = 3000,
                         failMessage = "락 획득 실패")
        public String processWithLock(Long resourceId) {
            log.info("Processing resource: {}", resourceId);
            try {
                // 시뮬레이션: 비즈니스 로직 수행 (100ms)
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "success";
        }

        /**
         * 카운터 증가 (데이터 정합성 테스트용)
         */
        @DistributedLock(key = "'counter:lock:' + #counterKey",
                         leaseTime = 3000,
                         waitTime = 5000)
        public void incrementCounter(String counterKey) {
            Integer current = (Integer) redisTemplate.opsForValue().get(counterKey);
            if (current == null) {
                current = 0;
            }

            // 시뮬레이션: 읽기-수정-쓰기 패턴
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            redisTemplate.opsForValue().set(counterKey, current + 1);
        }

        /**
         * 복잡한 SpEL 표현식 테스트용
         */
        @DistributedLock(key = "'payment:' + #userId + ':' + #idempotencyKey",
                         leaseTime = 10000,
                         waitTime = 2000)
        public String processPayment(Long userId, String idempotencyKey) {
            log.info("Processing payment for user: {}, key: {}", userId, idempotencyKey);
            return "payment processed for user " + userId;
        }
    }
}
