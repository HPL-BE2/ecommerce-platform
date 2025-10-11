package kr.hhplus.be.server.application.port.in;

public interface CompleteOrderUseCase {
    record Command(Long orderId) {}
    record Result(Long orderId, int total) {}

    Result complete(Command command);
}
