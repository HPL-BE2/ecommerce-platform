package kr.hhplus.be.server.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

public record Product(
        Long id,
        String sku,
        String name,
        BigDecimal price,
        Integer stock,
        String thumbnailUrl
//        String status
) {}
