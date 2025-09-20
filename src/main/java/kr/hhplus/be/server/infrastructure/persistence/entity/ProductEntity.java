package kr.hhplus.be.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "products")
@Getter
public class ProductEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable=false, unique=true, length=64)
    private String sku;

    @Column(nullable=false, length=200)
    private String name;

    @Column(nullable=false, precision=12, scale=0)
    private BigDecimal price; // KRW 정수 금액

    @Column(nullable=false)
    private Integer stock;

    @Column(name = "thumbnail_url")
    private String thumbnailUrl;

    @Column(nullable=false, updatable=false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private OffsetDateTime createdAt = OffsetDateTime.now();

}
