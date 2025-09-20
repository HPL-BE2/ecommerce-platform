CREATE TABLE IF NOT EXISTS products (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    sku VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    price DECIMAL(12,0) NOT NULL,
    stock INT NOT NULL,
    thumbnail_url VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
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

-- =========================
-- 2) 카탈로그
-- =========================
CREATE TABLE IF NOT EXISTS categories (
                                          id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                          name VARCHAR(100) NOT NULL,
    parent_id BIGINT NULL,
    CONSTRAINT fk_cat_parent FOREIGN KEY (parent_id) REFERENCES categories(id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS product_categories (
                                                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                                  product_id BIGINT NOT NULL,
                                                  category_id BIGINT NOT NULL,
                                                  UNIQUE KEY uq_prod_cat (product_id, category_id),
    CONSTRAINT fk_pc_p FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT fk_pc_c FOREIGN KEY (category_id) REFERENCES categories(id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS inventory (
                                         product_id BIGINT PRIMARY KEY,
                                         stock INT NOT NULL,
                                         safety_stock INT NOT NULL DEFAULT 0,
                                         updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                         CONSTRAINT fk_inv_prod FOREIGN KEY (product_id) REFERENCES products(id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS stock_movements (
                                               id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                               product_id BIGINT NOT NULL,
                                               qty INT NOT NULL,
                                               reason VARCHAR(32) NOT NULL,             -- ORDER/RESTOCK/CANCEL/ADJUST
    ref_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sm_prod FOREIGN KEY (product_id) REFERENCES products(id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =========================
-- 3) 주문/결제
-- =========================
CREATE TABLE IF NOT EXISTS orders (
                                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                      user_id BIGINT NOT NULL,
                                      order_no VARCHAR(32) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,             -- PENDING/RESERVED/PAID/CANCELED/FAILED
    discount BIGINT NOT NULL DEFAULT 0,
    total BIGINT NOT NULL,
    payment_method VARCHAR(20),              -- WALLET/CARD/...
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_ord_user FOREIGN KEY (user_id) REFERENCES users(id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_items (
                                           id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                           order_id BIGINT NOT NULL,
                                           product_id BIGINT NOT NULL,
                                           name VARCHAR(200) NOT NULL,
    qty INT NOT NULL,
    unit_price BIGINT NOT NULL,
    line_total BIGINT NOT NULL,
    CONSTRAINT fk_oi_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_oi_product FOREIGN KEY (product_id) REFERENCES products(id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS payments (
                                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                        order_id BIGINT NOT NULL,
                                        status VARCHAR(20) NOT NULL,             -- REQUEST/AUTHORIZE/CAPTURE/CANCELED/FAILED
    method VARCHAR(12) NOT NULL,             -- WALLET/CARD
    provider VARCHAR(64),
    pg_tid VARCHAR(64),
    amount BIGINT NOT NULL,
    requested_at TIMESTAMP NULL,
    approved_at TIMESTAMP NULL,
    canceled_at TIMESTAMP NULL,
    CONSTRAINT fk_pay_order FOREIGN KEY (order_id) REFERENCES orders(id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =========================
-- 4) 쿠폰
-- =========================
CREATE TABLE IF NOT EXISTS coupons (
                                       id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                       code VARCHAR(64) NOT NULL UNIQUE,
    type VARCHAR(16) NOT NULL,               -- PERCENT/FIXED
    value DECIMAL(10,2) NOT NULL,
    min_amount BIGINT,
    max_discount BIGINT,
    starts_at TIMESTAMP NULL,
    ends_at   TIMESTAMP NULL,
    usage_limit INT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS coupon_issuances (
                                                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                                coupon_id BIGINT NOT NULL,
                                                user_id BIGINT NOT NULL,
                                                issued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                redeem_count INT NOT NULL DEFAULT 0,
                                                UNIQUE KEY uq_issue (coupon_id, user_id),
    CONSTRAINT fk_ci_coupon FOREIGN KEY (coupon_id) REFERENCES coupons(id),
    CONSTRAINT fk_ci_user   FOREIGN KEY (user_id)   REFERENCES users(id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS coupon_redemptions (
                                                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                                  issuance_id BIGINT NOT NULL,
                                                  order_id BIGINT NOT NULL,
                                                  amount BIGINT NOT NULL,
                                                  redeemed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                  CONSTRAINT fk_cr_issue FOREIGN KEY (issuance_id) REFERENCES coupon_issuances(id),
    CONSTRAINT fk_cr_order FOREIGN KEY (order_id)     REFERENCES orders(id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =========================
-- 5) Outbox (이벤트 발행용)
-- =========================
CREATE TABLE IF NOT EXISTS outbox_events (
                                             id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                             aggregate_type VARCHAR(64) NOT NULL,  -- 예: 'order'
    aggregate_id   VARCHAR(64) NOT NULL,
    event_type     VARCHAR(64) NOT NULL,
    payload        JSON NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;