package kr.hhplus.be.server.interfaces.web;

import kr.hhplus.be.server.application.port.in.CreateOrderUseCase;
import kr.hhplus.be.server.application.port.in.CreateWalletTopupUseCase;
import kr.hhplus.be.server.application.port.in.IssueCouponUseCase;
import kr.hhplus.be.server.application.service.CouponService;
import kr.hhplus.be.server.application.service.OrderService;
import kr.hhplus.be.server.application.service.WalletService;
import kr.hhplus.be.server.infrastructure.persistence.repo.SpringCouponIssuanceJpa;
import kr.hhplus.be.server.infrastructure.persistence.repo.SpringWalletJpa;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시성 제어 통합 테스트
 *
 * 요구사항:
 * - 멀티스레드 환경에서 실제 동시성 문제 검증
 * - ExecutorService + CountDownLatch 사용
 * - 최종 데이터 정합성 확인
 */
@DisplayName("동시성 제어 통합 테스트")
class ConcurrencyControlIntegrationTest extends ControllerIntegrationTestSupport {

    @Autowired
    private OrderService orderService;

    @Autowired
    private WalletService walletService;

    @Autowired
    private CouponService couponService;

    @Autowired
    private SpringWalletJpa walletJpa;

    @Autowired
    private SpringCouponIssuanceJpa couponIssuanceJpa;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private Long testUserId;
    private Long testProductId;

    @BeforeEach
    void setUp() {
        // 테스트용 사용자 생성
        testUserId = createTestUser("concurrency-test-" + UUID.randomUUID() + "@example.com", "Concurrency Tester");

        // 테스트용 상품 생성
        testProductId = createTestProduct("Test Product " + UUID.randomUUID(), 10000);
    }

    @Test
    @DisplayName("재고 1개를 100명이 동시 주문 시 1명만 성공 (Optimistic Lock)")
    void testInventoryConcurrency_100Threads_Stock1_OnlyOneSucceeds() throws InterruptedException {
        // Given: 재고 1개 상품
        setStock(testProductId, 1);

        // When: 100개 스레드가 동시에 주문
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<Throwable> exceptions = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final long userId = createTestUser("stock-test-user-" + i + "@test.com", "Stock Test User " + i);
            topupBalance(userId, 100_000L); // 충분한 잔액 충전

            executor.submit(() -> {
                try {
                    orderService.create(new CreateOrderUseCase.Command(
                            userId,
                            List.of(new CreateOrderUseCase.Item(testProductId, 1)),
                            null, // couponCode
                            10000, // expectedTotal
                            "stock-test-" + UUID.randomUUID()
                    ));
                    successCount.incrementAndGet();
                } catch (ObjectOptimisticLockingFailureException | IllegalStateException e) {
                    // 재고 부족 or Optimistic Lock 충돌
                    failCount.incrementAndGet();
                } catch (Exception e) {
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(30, TimeUnit.SECONDS);

        executor.shutdown();

        // Then: 정확히 1명만 성공, 최종 재고 0
        System.out.println("=== 재고 감소 동시성 테스트 결과 ===");
        System.out.println("완료 여부: " + finished);
        System.out.println("성공: " + successCount.get() + "건");
        System.out.println("실패: " + failCount.get() + "건");
        System.out.println("최종 재고: " + getStock(testProductId));
        if (!exceptions.isEmpty()) {
            System.out.println("예외 발생: " + exceptions.size() + "건");
            exceptions.forEach(e -> System.out.println("  - " + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(99);
        assertThat(getStock(testProductId)).isEqualTo(0);
    }

    @Test
    @DisplayName("잔액 1000원으로 1000원 결제 2번 동시 시도 시 1번만 성공 (Pessimistic Lock)")
    void testBalanceConcurrency_2Threads_Balance1000_OnlyOneSucceeds() throws InterruptedException {
        // Given: 잔액 1000원, 1000원짜리 상품
        topupBalance(testUserId, 1000L);

        Long productId = createTestProduct("Balance Test Product " + UUID.randomUUID(), 1000);
        setStock(productId, 10); // 충분한 재고

        // When: 2개 스레드가 동시에 1000원 결제
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<Throwable> exceptions = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    orderService.create(new CreateOrderUseCase.Command(
                            testUserId,
                            List.of(new CreateOrderUseCase.Item(productId, 1)),
                            null, // couponCode
                            1000, // expectedTotal
                            "balance-test-" + UUID.randomUUID()
                    ));
                    successCount.incrementAndGet();
                } catch (IllegalStateException e) {
                    // 잔액 부족
                    failCount.incrementAndGet();
                } catch (Exception e) {
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Then: 1번만 성공, 최종 잔액 0원
        long finalBalance = getBalance(testUserId);
        System.out.println("=== 잔액 차감 동시성 테스트 결과 ===");
        System.out.println("완료 여부: " + finished);
        System.out.println("성공: " + successCount.get() + "건");
        System.out.println("실패: " + failCount.get() + "건");
        System.out.println("최종 잔액: " + finalBalance + "원");
        if (!exceptions.isEmpty()) {
            System.out.println("예외 발생: " + exceptions.size() + "건");
            exceptions.forEach(e -> System.out.println("  - " + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(1);
        assertThat(finalBalance).isEqualTo(0L);
    }

    @Test
    @DisplayName("선착순 10명 쿠폰에 50명 동시 요청 시 10명만 발급 (DB UNIQUE 제약)")
    void testCouponIssuanceConcurrency_50Threads_Limit10_Only10Succeed() throws InterruptedException {
        // Given: 선착순 10명 쿠폰 생성
        Long couponId = createTestCoupon("CONCURRENT-TEST-" + UUID.randomUUID(), 10);

        // When: 50개 스레드가 동시 발급 요청
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<Throwable> exceptions = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final long userId = createTestUser("coupon-test-user-" + i + "@test.com", "Coupon Test User " + i);

            executor.submit(() -> {
                try {
                    couponService.issue(new IssueCouponUseCase.Command(couponId, userId));
                    successCount.incrementAndGet();
                } catch (IllegalStateException e) {
                    // 쿠폰 소진 or 이미 발급
                    failCount.incrementAndGet();
                } catch (Exception e) {
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Then: 정확히 10명만 발급
        long issuedCount = couponIssuanceJpa.countByCouponId(couponId);

        // Redis 카운터도 확인 (분산락 + Redis Atomic Counter 검증)
        String redisCountKey = "coupon:" + couponId + ":issued";
        Integer redisCount = (Integer) redisTemplate.opsForValue().get(redisCountKey);

        System.out.println("=== 쿠폰 발급 동시성 테스트 결과 (분산락 + Redis Atomic Counter) ===");
        System.out.println("완료 여부: " + finished);
        System.out.println("성공: " + successCount.get() + "건");
        System.out.println("실패: " + failCount.get() + "건");
        System.out.println("DB 발급 수: " + issuedCount + "건");
        System.out.println("Redis 카운터: " + redisCount + "건");
        if (!exceptions.isEmpty()) {
            System.out.println("예외 발생: " + exceptions.size() + "건");
            exceptions.forEach(e -> System.out.println("  - " + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }

        assertThat(successCount.get()).isEqualTo(10);
        assertThat(failCount.get()).isEqualTo(40);
        assertThat(issuedCount).isEqualTo(10L);

        // Redis 카운터도 DB와 일치해야 함 (동기화 검증)
        assertThat(redisCount).isEqualTo(10);
    }

    // === Helper Methods ===

    private Long createTestUser(String email, String name) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO users (email, name) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, email);
            ps.setString(2, name);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private Long createTestProduct(String name, int price) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO products (sku, name, price, stock) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, "SKU-" + UUID.randomUUID());
            ps.setString(2, name);
            ps.setInt(3, price);
            ps.setInt(4, 0); // 초기 재고 0
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private Long createTestCoupon(String code, int maxIssuance) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO coupons (code, type, value, usage_limit, starts_at, ends_at) " +
                    "VALUES (?, 'PERCENT', 10, ?, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 1 DAY))",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, code);
            ps.setInt(2, maxIssuance);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private void setStock(Long productId, int stock) {
        jdbcTemplate.update(
                "INSERT INTO inventory (product_id, stock, safety_stock, updated_at) " +
                "VALUES (?, ?, 0, NOW()) " +
                "ON DUPLICATE KEY UPDATE stock = ?, updated_at = NOW()",
                productId, stock, stock
        );
    }

    private int getStock(Long productId) {
        Integer stock = jdbcTemplate.queryForObject(
                "SELECT stock FROM inventory WHERE product_id = ?",
                Integer.class,
                productId
        );
        return stock != null ? stock : 0;
    }

    private void topupBalance(Long userId, Long amount) {
        walletService.topup(new CreateWalletTopupUseCase.Command(
                userId,
                amount,
                "TEST-TOPUP-" + UUID.randomUUID(),
                null,
                null
        ));
    }

    private long getBalance(Long userId) {
        return walletJpa.findById(userId)
                .map(w -> w.getBalance())
                .orElse(0L);
    }
}
