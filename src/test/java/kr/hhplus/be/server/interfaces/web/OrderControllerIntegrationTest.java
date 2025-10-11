package kr.hhplus.be.server.interfaces.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hhplus.be.server.interfaces.web.dto.OrderDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderControllerIntegrationTest extends ControllerIntegrationTestSupport {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    kr.hhplus.be.server.infrastructure.persistence.repo.SpringProductJpa productJpa;

    @Autowired
    kr.hhplus.be.server.infrastructure.persistence.repo.SpringOrderJpa orderJpa;

    @Test
    @DisplayName("주문 생성 API는 쿠폰 할인과 함께 주문을 예약 상태로 저장한다")
    void createOrder_success() throws Exception {
        Long userId = jdbcTemplate.queryForObject(
                "select id from users where email = ?", Long.class, "alice@example.com");
        List<kr.hhplus.be.server.infrastructure.persistence.entity.ProductEntity> products =
                productJpa.findAll(Sort.by(Sort.Direction.ASC, "id"));

        var first = products.get(0);
        var second = products.get(1);
        int subtotal = asInt(first.getPrice()) + asInt(second.getPrice());
        int discount = Math.min((subtotal * 10) / 100, 20_000);
        int total = subtotal - discount;

        var request = new OrderDtos.CreateOrderRequest(
                userId,
                List.of(
                        new OrderDtos.CreateOrderItem(first.getId(), 1),
                        new OrderDtos.CreateOrderItem(second.getId(), 1)
                ),
                "WELCOME10",
                total
        );

        String requestKey = "itest-" + UUID.randomUUID();

        MvcResult mvcResult = mockMvc.perform(post("/api/v1/orders")
                        .contentType("application/json")
                        .header("Idempotency-Key", requestKey)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("RESERVED"))
                .andExpect(jsonPath("$.data.subtotal.amount").value(subtotal))
                .andExpect(jsonPath("$.data.discount.amount").value(discount))
                .andExpect(jsonPath("$.data.total.amount").value(total))
                .andReturn();

        JsonNode root = objectMapper.readTree(mvcResult.getResponse().getContentAsByteArray());
        long orderId = root.path("data").path("orderId").asLong();

        var savedOrder = orderJpa.findById(orderId);
        assertThat(savedOrder).isPresent();
        assertThat(savedOrder.get().getUserId()).isEqualTo(userId);
        assertThat(savedOrder.get().getTotal()).isEqualTo((long) total);
        assertThat(savedOrder.get().getStatus()).isEqualTo("RESERVED");
        assertThat(savedOrder.get().getCouponIssuanceId()).isNotNull();
        assertThat(savedOrder.get().getRequestKey()).isEqualTo(requestKey);
    }

    private static int asInt(BigDecimal value) {
        return value != null ? value.intValue() : 0;
    }
}
