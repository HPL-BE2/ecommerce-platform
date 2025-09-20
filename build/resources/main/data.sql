INSERT INTO products(sku, name, price, stock, thumbnail_url) VALUES
 ('SKU-1001','블루투스 이어폰',59000,27,'https://cdn/img/1001.png'),
 ('SKU-1002','기계식 키보드',129000,15,'https://cdn/img/1002.png'),
 ('SKU-1003','USB-C 허브',39000,44,'https://cdn/img/1003.png'),
 ('SKU-1004','게이밍 마우스',49000,32,'https://cdn/img/1004.png'),
 ('SKU-1005','27인치 모니터',299000,8,'https://cdn/img/1005.png');

INSERT IGNORE INTO users (id, email, name) VALUES
(1, 'alice@example.com', '앨리스'),
(2, 'bob@example.com',   '밥'),
(3, 'carol@example.com', '캐럴'),
(4, 'dave@example.com',  '데이브'),
(5, 'erin@example.com',  '에린');