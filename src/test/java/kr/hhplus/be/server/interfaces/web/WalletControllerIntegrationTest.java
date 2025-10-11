package kr.hhplus.be.server.interfaces.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hhplus.be.server.interfaces.web.dto.WalletDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WalletControllerIntegrationTest extends ControllerIntegrationTestSupport {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    kr.hhplus.be.server.infrastructure.persistence.repo.SpringWalletJpa walletJpa;

    @Autowired
    kr.hhplus.be.server.infrastructure.persistence.repo.SpringWalletTxJpa walletTxJpa;

    @Test
    @DisplayName("지갑 충전 API는 멱등키를 포함한 요청으로 잔액과 거래를 저장한다")
    void walletTopup_success() throws Exception {
        Long userId = jdbcTemplate.queryForObject(
                "select id from users where email = ?", Long.class, "bob@example.com");
        jdbcTemplate.update("delete from wallet_transactions where user_id = ?", userId);
        jdbcTemplate.update("delete from wallets where user_id = ?", userId);

        var request = new WalletDtos.TopupRequest(75_000L, "idem-itest", "TEST", "ref-123");

        MvcResult mvcResult = mockMvc.perform(post("/api/v1/wallets/{userId}/topups", userId)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.transactionId").isNumber())
                .andExpect(jsonPath("$.data.balanceAfter").value(75_000L))
                .andExpect(jsonPath("$.data.idempotent").value(false))
                .andReturn();

        JsonNode root = objectMapper.readTree(mvcResult.getResponse().getContentAsByteArray());
        long transactionId = root.path("data").path("transactionId").asLong();

        var wallet = walletJpa.findById(userId);
        assertThat(wallet).isPresent();
        assertThat(wallet.get().getBalance()).isEqualTo(75_000L);

        var savedTx = walletTxJpa.findById(transactionId);
        assertThat(savedTx).isPresent();
        assertThat(savedTx.get().getIdempotencyKey()).isEqualTo("idem-itest");
        assertThat(savedTx.get().getBalanceAfter()).isEqualTo(75_000L);
    }
}
