package kr.hhplus.be.server.layered.application.port.in;

public interface CreateWalletTopupUseCase {
    record Command(Long userId, Long amount, String idempotencyKey, String refType, String refId) { }
    record Result(Long transactionId, long balanceAfter, boolean idempotent) { }

    Result topup(Command command);
}
