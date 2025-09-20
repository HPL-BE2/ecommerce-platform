package kr.hhplus.be.server.layered.infrastructure.persistence.entity;


import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "wallets")
@Getter @Setter
public class WalletEntity {
    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private Long balance;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    public void onUpdate(){ this.updatedAt = OffsetDateTime.now(); }
}
