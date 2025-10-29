package kr.hhplus.be.server.interfaces.web;

import kr.hhplus.be.server.application.port.in.GetProductDetailUseCase;
import kr.hhplus.be.server.application.port.in.ListProductsUseCase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductsController.class)
class ProductsControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ListProductsUseCase listProductsUseCase;

    @MockitoBean
    GetProductDetailUseCase getProductDetailUseCase;

    @Test
    void list_returnsPagedProductsWrappedInEnvelope() throws Exception {
        var result = new ListProductsUseCase.Result(
                List.of(new ListProductsUseCase.Item(1L, "SKU-1", "T-Shirt", 12900, 50, "thumb.png")),
                999L
        );
        given(listProductsUseCase.list(any())).willReturn(result);

        mockMvc.perform(get("/api/v1/products")
                        .param("q", "shirt")
                        .param("categoryId", "3")
                        .param("sort", "price")
                        .param("limit", "30")
                        .param("cursor", "10")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(1))
                .andExpect(jsonPath("$.data.items[0].sku").value("SKU-1"))
                .andExpect(jsonPath("$.data.items[0].price.amount").value(12900))
                .andExpect(jsonPath("$.data.meta.nextCursor").value(999));

        ArgumentCaptor<ListProductsUseCase.Query> captor = ArgumentCaptor.forClass(ListProductsUseCase.Query.class);
        verify(listProductsUseCase).list(captor.capture());
        assertThat(captor.getValue())
                .isEqualTo(new ListProductsUseCase.Query(30, 10L, "shirt", 3L, "price"));
    }

    @Test
    void list_returnsBadRequestWhenLimitIsOutOfRange() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .param("limit", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/validation"));

        verifyNoInteractions(listProductsUseCase);
    }

    @Test
    void detail_returnsSingleProduct() throws Exception {
        var detailResult = new GetProductDetailUseCase.Result(5L, "SKU-5", "Sneakers", 99000, 5, "thumb-5.png");
        given(getProductDetailUseCase.get(any())).willReturn(detailResult);

        mockMvc.perform(get("/api/v1/products/{productId}", 5))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(5))
                .andExpect(jsonPath("$.data.price.amount").value(99000))
                .andExpect(jsonPath("$.data.currency").doesNotExist())
                .andExpect(jsonPath("$.data.price.currency").value("KRW"));

        ArgumentCaptor<GetProductDetailUseCase.Query> captor = ArgumentCaptor.forClass(GetProductDetailUseCase.Query.class);
        verify(getProductDetailUseCase).get(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new GetProductDetailUseCase.Query(5L));
    }
}
