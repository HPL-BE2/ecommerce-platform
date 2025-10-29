package kr.hhplus.be.server.interfaces.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hhplus.be.server.interfaces.web.dto.OrderDtos;
import kr.hhplus.be.server.interfaces.web.dto.WalletDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserFlowIntegrationTest extends ControllerIntegrationTestSupport {

    private static final Logger log = LoggerFactory.getLogger(UserFlowIntegrationTest.class);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("사용자 여정 전체 흐름이 Testcontainers 환경에서 정상 동작한다")
    void fullUserJourney_withRealComponents() throws Exception {
        Long userId = jdbcTemplate.queryForObject(
                "select id from users where email = ?", Long.class, "alice@example.com");
        assertThat(userId).withFailMessage("시드 사용자(alice)가 있어야 합니다").isNotNull();

        Long productId = jdbcTemplate.queryForObject(
                "select id from products where sku = ?", Long.class, "SKU-1001");
        assertThat(productId).withFailMessage("시드 상품 SKU-1001이 있어야 합니다").isNotNull();

        BigDecimal unitPriceDecimal = jdbcTemplate.queryForObject(
                "select price from products where id = ?", BigDecimal.class, productId);
        assertThat(unitPriceDecimal).isNotNull();

        Integer stockBefore = jdbcTemplate.queryForObject(
                "select stock from inventory where product_id = ?", Integer.class, productId);
        assertThat(stockBefore).isNotNull().isGreaterThanOrEqualTo(1);

        jdbcTemplate.update("delete from wallet_transactions where user_id = ?", userId);
        jdbcTemplate.update("delete from wallets where user_id = ?", userId);

        int quantity = Math.min(2, Math.max(1, stockBefore));
        int unitPrice = unitPriceDecimal.intValueExact();
        int expectedTotal = unitPrice * quantity;
        long topupAmount = expectedTotal + 10_000L;

        log.info("STEP 1 - 상품 목록 조회 시작");
        mockMvc.perform(get("/api/v1/products")
                        .accept(MediaType.APPLICATION_JSON)
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items[0].id").isNumber());
        log.info("STEP 1 - 상품 목록 조회 완료");

        log.info("STEP 2 - 상품 상세 조회 시작: productId={}", productId);
        mockMvc.perform(get("/api/v1/products/{productId}", productId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(productId))
                .andExpect(jsonPath("$.data.price.amount").value(unitPrice));
        log.info("STEP 2 - 상품 상세 조회 완료");

        log.info("STEP 3 - 지갑 충전 시작: userId={}, amount={}", userId, topupAmount);
        var topupRequest = new WalletDtos.TopupRequest(
                topupAmount,
                "wallet-flow-" + UUID.randomUUID(),
                "ORDER",
                "user-flow"
        );

        MvcResult topupResult = mockMvc.perform(post("/api/v1/wallets/{userId}/topups", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(topupRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.balanceAfter").value(topupAmount))
                .andExpect(jsonPath("$.data.idempotent").value(false))
                .andReturn();
        log.info("STEP 3 - 지갑 충전 완료");

        JsonNode topupJson = objectMapper.readTree(topupResult.getResponse().getContentAsByteArray());
        long walletTxId = topupJson.path("data").path("transactionId").asLong();
        assertThat(walletTxId).isGreaterThan(0);

        Long persistedBalance = jdbcTemplate.queryForObject(
                "select balance from wallets where user_id = ?", Long.class, userId);
        assertThat(persistedBalance).isEqualTo(topupAmount);

        Long persistedTxCount = jdbcTemplate.queryForObject(
                "select count(*) from wallet_transactions where user_id = ?", Long.class, userId);
        assertThat(persistedTxCount).isEqualTo(1);

        log.info("STEP 4 - 주문 생성 시작: productId={}, qty={}", productId, quantity);
        var orderRequest = new OrderDtos.CreateOrderRequest(
                userId,
                List.of(new OrderDtos.CreateOrderItem(productId, quantity)),
                null,
                expectedTotal
        );
        String orderIdem = "order-flow-" + UUID.randomUUID();

        MvcResult orderResult = mockMvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", orderIdem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(orderRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.total.amount").value(expectedTotal))
                .andReturn();
        log.info("STEP 4 - 주문 생성 완료");

        JsonNode orderJson = objectMapper.readTree(orderResult.getResponse().getContentAsByteArray());
        long orderId = orderJson.path("data").path("orderId").asLong();
        assertThat(orderId).isGreaterThan(0);

        var orderRow = jdbcTemplate.queryForMap(
                "select status, total, discount, request_key from orders where id = ?", orderId);
        assertThat(orderRow.get("status")).isEqualTo("RESERVED");
        assertThat(((Number) orderRow.get("total")).intValue()).isEqualTo(expectedTotal);
        assertThat(((Number) orderRow.get("discount")).intValue()).isEqualTo(0);
        assertThat(orderRow.get("request_key")).isEqualTo(orderIdem);

        Integer stockAfter = jdbcTemplate.queryForObject(
                "select stock from inventory where product_id = ?", Integer.class, productId);
        assertThat(stockAfter).isEqualTo(stockBefore - quantity);

        log.info("STEP 5 - 주문 확정 시작: orderId={}", orderId);
        mockMvc.perform(patch("/api/v1/orders/{orderId}/complete", orderId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(orderId))
                .andExpect(jsonPath("$.data.total").value(expectedTotal));
        log.info("STEP 5 - 주문 확정 완료");

        var completedRow = jdbcTemplate.queryForMap(
                "select status from orders where id = ?", orderId);
        assertThat(completedRow.get("status")).isEqualTo("COMPLETED");

        Integer outboxCount = jdbcTemplate.queryForObject(
                "select count(*) from outbox_events where aggregate_id = ?", Integer.class, String.valueOf(orderId));
        assertThat(outboxCount).isGreaterThanOrEqualTo(1);
    }
}
