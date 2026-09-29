-- 상품 → 옵션(product_sku.product_id), 옵션 → 사이즈(product_size.skuproduct_id) 외래 키 인덱스 (docs/59 §5).
--
-- PostgreSQL 은 외래 키에 인덱스를 저절로 만들지 않는다. 기준 스키마(V5)에 이 둘이 빠져 있어서 "이 상품의 옵션들",
-- "이 옵션의 사이즈들" 을 읽을 때마다 표 전체를 훑었다 — 상품 상세의 지연 로딩, 재고 차감·예약의 SKU 잠금 조회
-- (findForStockUpdate: product_id 로 거른다), 그리고 이번에 더한 목록 카드의 지금 수량 조회(한 페이지의 상품 id 로 조인)가
-- 모두 이 경로다.
--
-- 상품 수가 적은 지금은 잠금이 짧다. 표가 커지면 CREATE INDEX CONCURRENTLY 를 트랜잭션 밖에서 먼저 돌린다 — IF NOT EXISTS 라
-- 그 뒤 이 파일은 아무것도 하지 않는다.
CREATE INDEX IF NOT EXISTS idx_product_sku_product_id ON product.product_sku (product_id);
CREATE INDEX IF NOT EXISTS idx_product_size_skuproduct_id ON product.product_size (skuproduct_id);
