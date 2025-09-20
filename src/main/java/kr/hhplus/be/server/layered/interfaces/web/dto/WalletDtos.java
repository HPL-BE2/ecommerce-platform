package kr.hhplus.be.server.layered.interfaces.web.dto;

public class WalletDtos {
    public record TopupRequest(Long amount, String idempotencyKey, String refType, String refId) {}
    public record TopupResponse(Long transactionId, long balanceAfter, boolean idempotent) {}
}
