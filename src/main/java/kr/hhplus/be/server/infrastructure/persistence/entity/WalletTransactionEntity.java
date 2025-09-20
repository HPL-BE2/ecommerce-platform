package kr.hhplus.be.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "wallet_transactions",
        indexes = {
                @Index(name = "idx_wt_user_created", columnList = "user_id, created_at"),
                @Index(name = "idx_wt_idem", columnList = "user_id, idempotency_key", unique = true)
        })
@Data
public class WalletTransactionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="user_id", nullable = false) private Long userId;
    @Column(nullable = false, length = 32)     private String type; // TOPUP/DEBIT/...
    @Column(nullable = false)                  private Long amount;
    @Column(name="balance_after", nullable=false) private Long balanceAfter;
    @Column(name="ref_type")                   private String refType;
    @Column(name="ref_id")                     private String refId;
    @Column(name="idempotency_key", length=100) private String idempotencyKey;

    @Column(name="created_at", nullable=false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public WalletTransactionEntity(Long userId, String type, Long amount, Long balanceAfter,
                                   String refType, String refId, String idempotencyKey,
                                   OffsetDateTime createdAt) {
        this.userId = userId; this.type = type; this.amount = amount;
        this.balanceAfter = balanceAfter; this.refType = refType; this.refId = refId;
        this.idempotencyKey = idempotencyKey; this.createdAt = createdAt;
    }
}
