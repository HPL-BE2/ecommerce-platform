package kr.hhplus.be.server.interfaces.web;

import kr.hhplus.be.server.application.port.in.CreateOrderUseCase;
import kr.hhplus.be.server.interfaces.web.dto.ApiEnvelope;
import kr.hhplus.be.server.interfaces.web.dto.OrderDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {
    private final CreateOrderUseCase createOrder;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiEnvelope<OrderDtos.CreateOrderResponse> create(
            @RequestHeader("Idempotency-Key") String idemKey,
            @RequestBody OrderDtos.CreateOrderRequest req) {

        var cmd = new CreateOrderUseCase.Command(
                req.userId(), req.items().stream()
                .map(i -> new CreateOrderUseCase.Item(i.productId(), i.qty())).toList(),
                req.couponCode(), req.expectedTotal(), idemKey
        );
        var r = createOrder.create(cmd);
        return new ApiEnvelope<>(
                new OrderDtos.CreateOrderResponse(
                        r.orderId(), r.status(),
                        new OrderDtos.CreateOrderResponse.Money(r.subtotal(), "KRW"),
                        new OrderDtos.CreateOrderResponse.Money(r.discount(), "KRW"),
                        new OrderDtos.CreateOrderResponse.Money(r.total(), "KRW")
                )
        );
    }
}
