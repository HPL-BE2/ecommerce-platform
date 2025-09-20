CREATE TABLE IF NOT EXISTS products (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    sku VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    price DECIMAL(12,0) NOT NULL,
    stock INT NOT NULL,
    thumbnail_url VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);


-- =========================
-- 1) 사용자 / 지갑
-- =========================
CREATE TABLE IF NOT EXISTS users (
     id BIGINT PRIMARY KEY AUTO_INCREMENT,
     email VARCHAR(255) NOT NULL UNIQUE,
    name  VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS wallets (
   user_id   BIGINT    NOT NULL,
   balance   BIGINT    NOT NULL DEFAULT 0,
   created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
   updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
   PRIMARY KEY (user_id),
    CONSTRAINT fk_wallet_user FOREIGN KEY (user_id) REFERENCES users(id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS wallet_transactions (
                                                   id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                                   user_id BIGINT NOT NULL,
                                                   type VARCHAR(32) NOT NULL,               -- TOPUP/DEBIT/REFUND/ADJUST
    amount BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    ref_type VARCHAR(64),                    -- ORDER/TOPUP/ADMIN 등
    ref_id   VARCHAR(64),
    idempotency_key VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_wt_user_idem (user_id, idempotency_key),
    INDEX idx_wt_user_created (user_id, created_at),
    CONSTRAINT fk_wt_user FOREIGN KEY (user_id) REFERENCES users(id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;