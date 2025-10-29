package kr.hhplus.be.server.application.port.in;

public interface CreateWalletDebitUseCase {
    record Command(Long userId, Long amount, String idempotencyKey, String refType, String refId) { }
    record Result(Long transactionId, long balanceAfter, boolean idempotent) { }

    Result debit(Command command);
}
