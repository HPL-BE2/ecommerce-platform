package kr.hhplus.be.server.domain.port.out;

import kr.hhplus.be.server.domain.port.out.dto.OrderCompletedEvent;

public interface OrderEventPublisher {
    void publish(OrderCompletedEvent event);
}
