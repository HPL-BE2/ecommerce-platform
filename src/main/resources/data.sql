-- INSERT INTO products(sku, name, price, stock, thumbnail_url) VALUES
--  ('SKU-1001','블루투스 이어폰',59000,27,'https://cdn/img/1001.png'),
--  ('SKU-1002','기계식 키보드',129000,15,'https://cdn/img/1002.png'),
--  ('SKU-1003','USB-C 허브',39000,44,'https://cdn/img/1003.png'),
--  ('SKU-1004','게이밍 마우스',49000,32,'https://cdn/img/1004.png'),
--  ('SKU-1005','27인치 모니터',299000,8,'https://cdn/img/1005.png');

-- -- 2) 상품 (ACTIVE)
-- INSERT IGNORE INTO products (id, sku, name, price, status, thumbnail_url) VALUES
-- (1, 'SKU-1001', '블루투스 이어폰', 59000, 'ACTIVE', 'https://cdn/img/1001.png'),
-- (2, 'SKU-1002', '기계식 키보드', 129000, 'ACTIVE', 'https://cdn/img/1002.png'),
-- (3, 'SKU-1003', 'USB-C 허브', 39000, 'ACTIVE', 'https://cdn/img/1003.png');

INSERT IGNORE INTO users (id, email, name) VALUES
(1, 'alice@example.com', '앨리스'),
(2, 'bob@example.com',   '밥'),
(3, 'carol@example.com', '캐럴'),
(4, 'dave@example.com',  '데이브'),
(5, 'erin@example.com',  '에린');

-- 3) 재고
INSERT IGNORE INTO inventory (product_id, stock, safety_stock, updated_at) VALUES
(1, 50, 0, NOW()),
(2, 20, 0, NOW()),
(3, 30, 0, NOW());

       -- 4) (선택) 쿠폰: 10% (최대 20,000원), 오늘~내일 유효
INSERT IGNORE INTO coupons
(code, type, value, min_amount, max_discount, starts_at, ends_at)
VALUES ('WELCOME10', 'PERCENT', 10, 50000, 20000, NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY));

       -- 쿠폰 발급(사용자 1에게)
INSERT IGNORE INTO coupon_issuances (coupon_id, user_id, redeem_count)
SELECT id, 1, 0 FROM coupons WHERE code = 'WELCOME10';