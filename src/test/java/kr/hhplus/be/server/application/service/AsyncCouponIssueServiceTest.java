package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.application.port.in.RequestCouponIssueUseCase;
import kr.hhplus.be.server.domain.model.Coupon;
import kr.hhplus.be.server.domain.port.out.CouponReadWritePort;
import kr.hhplus.be.server.infrastructure.coupon.CouponIssueLuaScriptExecutor;
import kr.hhplus.be.server.infrastructure.coupon.CouponIssueMessage;
import kr.hhplus.be.server.infrastructure.coupon.CouponIssueMessagePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class AsyncCouponIssueServiceTest {

    @Mock
    CouponReadWritePort couponPort;
    @Mock
    CouponIssueLuaScriptExecutor scriptExecutor;
    @Mock
    CouponIssueMessagePublisher messagePublisher;
    @Mock
    RedisTemplate<String, String> counterRedisTemplate;
    @Mock
    ValueOperations<String, String> valueOperations;

    @InjectMocks
    AsyncCouponIssueService service;

    private Coupon coupon;

    @BeforeEach
    void setUp() {
        coupon = new Coupon(
                10L, "CODE", "FIXED", 1000, 0, null, 5,
                OffsetDateTime.now().minusMinutes(1),
                OffsetDateTime.now().plusHours(1)
        );
        lenient().when(counterRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void request_whenReserved_publishesMessage() {
        given(couponPort.findById(10L)).willReturn(Optional.of(coupon));
        given(couponPort.isAlreadyIssued(10L, 1L)).willReturn(false);
        given(counterRedisTemplate.hasKey(any())).willReturn(Boolean.FALSE);
        given(scriptExecutor.tryAcquire(10L, 1L)).willReturn(CouponIssueLuaScriptExecutor.Decision.RESERVED);

        RequestCouponIssueUseCase.Result result = service.request(new RequestCouponIssueUseCase.Command(10L, 1L));

        assertThat(result.status()).isEqualTo("ACCEPTED");
        ArgumentCaptor<CouponIssueMessage> captor = ArgumentCaptor.forClass(CouponIssueMessage.class);
        verify(messagePublisher).publish(captor.capture());
        assertThat(captor.getValue().couponId()).isEqualTo(10L);
        assertThat(captor.getValue().userId()).isEqualTo(1L);
        verify(valueOperations).set(any(), eq("5"));
    }

    @Test
    void request_whenSoldOut_throws() {
        given(couponPort.findById(10L)).willReturn(Optional.of(coupon));
        given(couponPort.isAlreadyIssued(10L, 1L)).willReturn(false);
        given(counterRedisTemplate.hasKey(any())).willReturn(Boolean.TRUE);
        given(scriptExecutor.tryAcquire(10L, 1L)).willReturn(CouponIssueLuaScriptExecutor.Decision.SOLD_OUT);

        assertThatThrownBy(() -> service.request(new RequestCouponIssueUseCase.Command(10L, 1L)))
                .isInstanceOf(IllegalStateException.class);

        verify(messagePublisher, never()).publish(any());
    }
}
