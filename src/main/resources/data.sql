-- ============================================
-- Seed: users, products, inventory, coupons, issuance
-- MySQL 8.x
-- 여러 번 실행해도 데이터가 중복되지 않도록 UPSERT 사용
-- ============================================

START TRANSACTION;

-- 0) 필수 테이블이 없을 때를 대비 (이미 있으시면 무시됩니다)
-- users, products, inventory, coupons, coupon_issuances 는 질문에 제공된 스키마 사용 가정

-- 1) 사용자
INSERT INTO users (email, name)
VALUES
    ('alice@example.com', '앨리스'),
    ('bob@example.com',   '밥'),
    ('carol@example.com', '캐럴'),
    ('dave@example.com',  '데이브'),
    ('erin@example.com',  '에린')
    ON DUPLICATE KEY UPDATE
                         name = VALUES(name);

-- 2) 상품 (products.stock 컬럼과 inventory.stock 둘 다 초기 동기화)
INSERT INTO products (sku, name, price, stock, thumbnail_url)
VALUES
    ('SKU-1001','블루투스 이어폰',  59000, 27,'https://cdn/img/1001.png'),
    ('SKU-1002','기계식 키보드',  129000, 15,'https://cdn/img/1002.png'),
    ('SKU-1003','USB-C 허브',       39000, 44,'https://cdn/img/1003.png'),
    ('SKU-1004','게이밍 마우스',     49000, 32,'https://cdn/img/1004.png'),
    ('SKU-1005','27인치 모니터',   299000,  8,'https://cdn/img/1005.png')
    ON DUPLICATE KEY UPDATE
                         name = VALUES(name),
                         price = VALUES(price),
                         stock = VALUES(stock),
                         thumbnail_url = VALUES(thumbnail_url);

-- 3) 재고 (inventory): 제품 sku로 id를 찾아 안전하게 매핑
--    safety_stock 은 0으로, updated_at 은 NOW()로 초기화
INSERT INTO inventory (product_id, stock, safety_stock, updated_at)
SELECT p.id, p.stock, 0, NOW()
FROM products p
WHERE p.sku IN ('SKU-1001','SKU-1002','SKU-1003','SKU-1004','SKU-1005')
    ON DUPLICATE KEY UPDATE
                         stock = VALUES(stock),
                         safety_stock = VALUES(safety_stock),
                         updated_at = VALUES(updated_at);

-- 4) 쿠폰
INSERT INTO coupons
(code, type, value, min_amount, max_discount, starts_at, ends_at, usage_limit)
VALUES
    ('WELCOME10', 'PERCENT', 10.00, 50000, 20000, NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY), NULL)
    ON DUPLICATE KEY UPDATE
                         type         = VALUES(type),
                         value        = VALUES(value),
                         min_amount   = VALUES(min_amount),
                         max_discount = VALUES(max_discount),
                         starts_at    = VALUES(starts_at),
                         ends_at      = VALUES(ends_at),
                         usage_limit  = VALUES(usage_limit);

-- 5) 쿠폰 발급: alice 에게 1장 (없으면 발급, 있으면 유지)
INSERT INTO coupon_issuances (coupon_id, user_id, redeem_count)
SELECT c.id, u.id, 0
FROM coupons c
         JOIN users u ON u.email = 'alice@example.com'
WHERE c.code = 'WELCOME10'
    ON DUPLICATE KEY UPDATE
                         redeem_count = coupon_issuances.redeem_count;

COMMIT;

-- 주문 테이블에 쿠폰 발급 컬럼/제약 조건이 없다면 추가
SET @has_order_coupon := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'orders'
      AND column_name = 'coupon_issuance_id'
);
SET @ddl := IF(
    @has_order_coupon = 0,
    'ALTER TABLE orders ADD COLUMN coupon_issuance_id BIGINT NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_order_coupon_fk := (
    SELECT COUNT(*)
    FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE()
      AND table_name = 'orders'
      AND constraint_name = 'fk_orders_coupon_issuance'
);
SET @ddl := IF(
    @has_order_coupon_fk = 0,
    'ALTER TABLE orders ADD CONSTRAINT fk_orders_coupon_issuance FOREIGN KEY (coupon_issuance_id) REFERENCES coupon_issuances(id)',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_request_key := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'orders'
      AND column_name = 'request_key'
);
SET @ddl := IF(
    @has_request_key = 0,
    'ALTER TABLE orders ADD COLUMN request_key VARCHAR(100) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_request_key_nullable := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'orders'
      AND column_name = 'request_key'
      AND is_nullable = 'YES'
);
SET @needs_request_key_fill := (
    SELECT IFNULL(COUNT(*), 0)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'orders'
      AND column_name = 'request_key'
);
SET @sql := IF(
    @needs_request_key_fill > 0,
    'UPDATE orders SET request_key = CONCAT(\"MIG-\", id) WHERE request_key IS NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := IF(
    @has_request_key_nullable > 0,
    'ALTER TABLE orders MODIFY COLUMN request_key VARCHAR(100) NOT NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_request_key_idx := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'orders'
      AND index_name = 'uq_orders_user_request'
);
SET @ddl := IF(
    @has_request_key_idx = 0,
    'ALTER TABLE orders ADD CONSTRAINT uq_orders_user_request UNIQUE KEY (user_id, request_key)',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- MySQL 5.7/8.0 호환을 위해 컬럼 존재 여부를 확인한 뒤 필요한 경우만 DDL 실행
SET @has_status := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'outbox_events'
      AND column_name = 'status'
);
SET @ddl := IF(
    @has_status = 0,
    'ALTER TABLE outbox_events ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT ''PENDING''',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_retry_count := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'outbox_events'
      AND column_name = 'retry_count'
);
SET @ddl := IF(
    @has_retry_count = 0,
    'ALTER TABLE outbox_events ADD COLUMN retry_count INT NOT NULL DEFAULT 0',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_next_retry_at := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'outbox_events'
      AND column_name = 'next_retry_at'
);
SET @ddl := IF(
    @has_next_retry_at = 0,
    'ALTER TABLE outbox_events ADD COLUMN next_retry_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_last_error := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'outbox_events'
      AND column_name = 'last_error'
);
SET @ddl := IF(
    @has_last_error = 0,
    'ALTER TABLE outbox_events ADD COLUMN last_error TEXT NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_sent_at := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'outbox_events'
      AND column_name = 'sent_at'
);
SET @ddl := IF(
    @has_sent_at = 0,
    'ALTER TABLE outbox_events ADD COLUMN sent_at TIMESTAMP NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 확인용(선택)
-- SELECT id, sku, name, price, stock FROM products ORDER BY id;
-- SELECT * FROM inventory ORDER BY product_id;
-- SELECT * FROM coupons WHERE code='WELCOME10';
-- SELECT i.* FROM coupon_issuances i JOIN users u ON u.id=i.user_id WHERE u.email='alice@example.com';
