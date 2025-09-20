package kr.hhplus.be.server.layered.interfaces.web;

import kr.hhplus.be.server.layered.application.port.in.CreateWalletTopupUseCase;
import kr.hhplus.be.server.layered.interfaces.web.dto.ApiEnvelope;
import kr.hhplus.be.server.layered.interfaces.web.dto.WalletDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class WalletController {
    private final CreateWalletTopupUseCase topupUseCase;

    @PostMapping("/{userId}/topups")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiEnvelope<WalletDtos.TopupResponse> topup(@PathVariable Long userId,
                                                       @RequestBody WalletDtos.TopupRequest req) {
        var res = topupUseCase.topup(
                new CreateWalletTopupUseCase.Command(
                        userId, req.amount(), req.idempotencyKey(), req.refType(), req.refId()
                )
        );
        // 멱등 재요청이면 200으로 내려주고 싶으면 로직 분기해서 ResponseStatus 바꿔도 됨
        return new ApiEnvelope<>(new WalletDtos.TopupResponse(res.transactionId(), res.balanceAfter(), res.idempotent()));
    }
}
