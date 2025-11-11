package kr.hhplus.be.server.interfaces.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hhplus.be.server.application.port.in.CompleteOrderUseCase;
import kr.hhplus.be.server.application.port.in.CreateOrderUseCase;
import kr.hhplus.be.server.interfaces.web.dto.OrderDtos;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    CreateOrderUseCase createOrderUseCase;

    @MockitoBean
    CompleteOrderUseCase completeOrderUseCase;

    @Test
    void create_returnsCreatedOrderEnvelope() throws Exception {
        var request = new OrderDtos.CreateOrderRequest(
                1L,
                List.of(new OrderDtos.CreateOrderItem(100L, 2)),
                "COUPON-10P",
                25000
        );

        var result = new CreateOrderUseCase.Result(321L, "PLACED", 26000, 1000, 25000);
        given(createOrderUseCase.create(any())).willReturn(result);

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "req-123")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.orderId").value(321))
                .andExpect(jsonPath("$.data.status").value("PLACED"))
                .andExpect(jsonPath("$.data.subtotal.amount").value(26000))
                .andExpect(jsonPath("$.data.discount.currency").value("KRW"))
                .andExpect(jsonPath("$.data.total.amount").value(25000));

        ArgumentCaptor<CreateOrderUseCase.Command> captor = ArgumentCaptor.forClass(CreateOrderUseCase.Command.class);
        verify(createOrderUseCase).create(captor.capture());
        assertThat(captor.getValue())
                .isEqualTo(new CreateOrderUseCase.Command(
                        1L,
                        List.of(new CreateOrderUseCase.Item(100L, 2)),
                        "COUPON-10P",
                        25000,
                        "req-123"
                ));
    }
}
