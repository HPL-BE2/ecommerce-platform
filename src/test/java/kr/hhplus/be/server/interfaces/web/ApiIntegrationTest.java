package kr.hhplus.be.server.interfaces.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hhplus.be.server.application.port.in.CompleteOrderUseCase;
import kr.hhplus.be.server.application.port.in.CreateOrderUseCase;
import kr.hhplus.be.server.application.port.in.CreateWalletTopupUseCase;
import kr.hhplus.be.server.application.port.in.GetProductDetailUseCase;
import kr.hhplus.be.server.application.port.in.ListProductsUseCase;
import kr.hhplus.be.server.interfaces.web.dto.OrderDtos;
import kr.hhplus.be.server.interfaces.web.dto.WalletDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

@WebMvcTest(controllers = {
        ProductsController.class,
        OrderController.class,
        WalletController.class
})
class ApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ListProductsUseCase listProductsUseCase;

    @MockitoBean
    private GetProductDetailUseCase getProductDetailUseCase;

    @MockitoBean
    private CreateOrderUseCase createOrderUseCase;

    @MockitoBean
    private CompleteOrderUseCase completeOrderUseCase;

    @MockitoBean
    private CreateWalletTopupUseCase createWalletTopupUseCase;

    @Test
    @DisplayName("GET /api/v1/products returns the product catalog with paging info")
    void listProducts_success() throws Exception {
        var items = List.of(
                new ListProductsUseCase.Item(1L, "SKU-001", "Coffee Beans", 15000, 30, "https://cdn.local/coffee.jpg")
        );
        when(listProductsUseCase.list(any(ListProductsUseCase.Query.class)))
                .thenReturn(new ListProductsUseCase.Result(items, 101L));

        mockMvc.perform(get("/api/v1/products")
                        .param("limit", "10")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.items[0].id").value(1))
                .andExpect(jsonPath("$.data.items[0].sku").value("SKU-001"))
                .andExpect(jsonPath("$.data.items[0].name").value("Coffee Beans"))
                .andExpect(jsonPath("$.data.items[0].price.amount").value(15000))
                .andExpect(jsonPath("$.data.items[0].price.currency").value("KRW"))
                .andExpect(jsonPath("$.data.items[0].stock").value(30))
                .andExpect(jsonPath("$.data.items[0].thumbnailUrl").value("https://cdn.local/coffee.jpg"))
                .andExpect(jsonPath("$.data.meta.nextCursor").value(101));
    }

    @Test
    @DisplayName("GET /api/v1/products/{productId} returns a single product detail")
    void getProductDetail_success() throws Exception {
        when(getProductDetailUseCase.get(any(GetProductDetailUseCase.Query.class)))
                .thenReturn(new GetProductDetailUseCase.Result(
                        1L, "SKU-001", "Premium Coffee", 18000, 20, "https://cdn.local/premium.jpg"
                ));

        mockMvc.perform(get("/api/v1/products/{productId}", 1L)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.sku").value("SKU-001"))
                .andExpect(jsonPath("$.data.name").value("Premium Coffee"))
                .andExpect(jsonPath("$.data.price.amount").value(18000))
                .andExpect(jsonPath("$.data.price.currency").value("KRW"))
                .andExpect(jsonPath("$.data.stock").value(20))
                .andExpect(jsonPath("$.data.thumbnailUrl").value("https://cdn.local/premium.jpg"));
    }

    @Test
    @DisplayName("POST /api/v1/orders creates a new order with idempotency header")
    void createOrder_success() throws Exception {
        when(createOrderUseCase.create(any(CreateOrderUseCase.Command.class)))
                .thenReturn(new CreateOrderUseCase.Result(555L, "PENDING", 30000, 5000, 25000));

        var request = new OrderDtos.CreateOrderRequest(
                42L,
                List.of(new OrderDtos.CreateOrderItem(1L, 2)),
                "COUPON-10",
                25000
        );

        mockMvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", "idem-key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.orderId").value(555))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.subtotal.amount").value(30000))
                .andExpect(jsonPath("$.data.discount.amount").value(5000))
                .andExpect(jsonPath("$.data.total.amount").value(25000))
                .andExpect(jsonPath("$.data.total.currency").value("KRW"));
    }

    @Test
    @DisplayName("PATCH /api/v1/orders/{orderId}/complete completes an order")
    void completeOrder_success() throws Exception {
        when(completeOrderUseCase.complete(any(CompleteOrderUseCase.Command.class)))
                .thenReturn(new CompleteOrderUseCase.Result(555L, 25000));

        mockMvc.perform(patch("/api/v1/orders/{orderId}/complete", 555L)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.orderId").value(555))
                .andExpect(jsonPath("$.data.total").value(25000));
    }

    @Test
    @DisplayName("POST /api/v1/wallets/{userId}/topups tops up a wallet balance")
    void walletTopup_success() throws Exception {
        when(createWalletTopupUseCase.topup(any(CreateWalletTopupUseCase.Command.class)))
                .thenReturn(new CreateWalletTopupUseCase.Result(321L, 45000L, false));

        var request = new WalletDtos.TopupRequest(20000L, "wallet-idem", "ORDER", "order-555");

        mockMvc.perform(post("/api/v1/wallets/{userId}/topups", 99L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.transactionId").value(321))
                .andExpect(jsonPath("$.data.balanceAfter").value(45000))
                .andExpect(jsonPath("$.data.idempotent").value(false));
    }
}
