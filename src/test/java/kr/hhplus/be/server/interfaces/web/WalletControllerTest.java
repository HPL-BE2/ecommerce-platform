package kr.hhplus.be.server.interfaces.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hhplus.be.server.application.port.in.CreateWalletTopupUseCase;
import kr.hhplus.be.server.interfaces.web.dto.WalletDtos;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WalletController.class)
class WalletControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    CreateWalletTopupUseCase topupUseCase;

    @Test
    void topup_returnsCreatedResponseEnvelope() throws Exception {
        var request = new WalletDtos.TopupRequest(50_000L, "idem-1", "ORDER", "order-1");
        var result = new CreateWalletTopupUseCase.Result(77L, 120_000L, false);
        given(topupUseCase.topup(any())).willReturn(result);

        mockMvc.perform(post("/api/v1/wallets/{userId}/topups", 9)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.transactionId").value(77))
                .andExpect(jsonPath("$.data.balanceAfter").value(120_000))
                .andExpect(jsonPath("$.data.idempotent").value(false));

        ArgumentCaptor<CreateWalletTopupUseCase.Command> captor = ArgumentCaptor.forClass(CreateWalletTopupUseCase.Command.class);
        verify(topupUseCase).topup(captor.capture());
        assertThat(captor.getValue())
                .isEqualTo(new CreateWalletTopupUseCase.Command(9L, 50_000L, "idem-1", "ORDER", "order-1"));
    }
}
