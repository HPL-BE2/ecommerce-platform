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

-- 확인용(선택)
-- SELECT id, sku, name, price, stock FROM products ORDER BY id;
-- SELECT * FROM inventory ORDER BY product_id;
-- SELECT * FROM coupons WHERE code='WELCOME10';
-- SELECT i.* FROM coupon_issuances i JOIN users u ON u.id=i.user_id WHERE u.email='alice@example.com';