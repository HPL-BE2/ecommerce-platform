package kr.hhplus.be.server.interfaces.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProductsControllerIntegrationTest extends ControllerIntegrationTestSupport {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    kr.hhplus.be.server.infrastructure.persistence.repo.SpringProductJpa productJpa;

    @Test
    @DisplayName("상품 목록 API는 기본 시드 데이터를 페이지 처럼 감싸서 반환한다")
    void listProducts_success() throws Exception {
        List<kr.hhplus.be.server.infrastructure.persistence.entity.ProductEntity> products =
                productJpa.findAll(Sort.by(Sort.Direction.ASC, "id"));

        mockMvc.perform(get("/api/v1/products").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].id").value(products.get(0).getId()))
                .andExpect(jsonPath("$.data.items[0].sku").value(products.get(0).getSku()))
                .andExpect(jsonPath("$.data.items[0].name").value(products.get(0).getName()))
                .andExpect(jsonPath("$.data.items[0].price.amount").value(asInt(products.get(0).getPrice())))
                .andExpect(jsonPath("$.data.items[0].stock").value(products.get(0).getStock()))
                .andExpect(jsonPath("$.data.items[0].price.currency").value("KRW"))
                .andExpect(jsonPath("$.data.meta.nextCursor").value(products.get(1).getId()));
    }

    @Test
    @DisplayName("상품 상세 API는 요청한 상품을 감싼 ApiEnvelope 형태로 돌려준다")
    void getProductDetail_success() throws Exception {
        var product = productJpa.findAll(Sort.by(Sort.Direction.ASC, "id")).get(0);

        mockMvc.perform(get("/api/v1/products/{productId}", product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(product.getId()))
                .andExpect(jsonPath("$.data.sku").value(product.getSku()))
                .andExpect(jsonPath("$.data.name").value(product.getName()))
                .andExpect(jsonPath("$.data.price.amount").value(asInt(product.getPrice())))
                .andExpect(jsonPath("$.data.price.currency").value("KRW"))
                .andExpect(jsonPath("$.data.thumbnailUrl", is(product.getThumbnailUrl())))
                .andExpect(jsonPath("$.data.stock").value(product.getStock()));
    }

    private static int asInt(BigDecimal value) {
        return value != null ? value.intValue() : 0;
    }
}
