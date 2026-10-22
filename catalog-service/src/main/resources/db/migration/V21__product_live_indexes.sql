-- 살아 있는 상품(status <> 'DELETED')만 담는 인덱스 둘 — 카테고리 개수 세기 · 이름 부분 일치 검색.
--
-- 2026-10-19 데이터 규모 측정(상품 100만, docs/83 §5): 앱의 상품 질의에는 늘 `status <> 'DELETED'` 가 붙는다
-- (Product 의 @SQLRestriction). status 는 어느 인덱스에도 없어서
--   ① 카테고리 개수 세기(검색의 모든 쪽 · 필터 검색)가 V20 인덱스로 줄을 찾고도 표를 다시 읽었다 — 13만 개 카테고리 한 번에
--      Bitmap Heap Scan 46,010쪽 · 102ms(조건 없이는 Index Only Scan 20ms). 깊은 쪽 검색 p50 155ms 의 대부분.
--   ② 이름 검색 `lower(name) LIKE '%…%'` 는 앞 와일드카드라 B-tree 를 못 써 개수 세기 · 목록이 각각 표 전체를 훑었다
--      (Parallel Seq Scan 178ms + 목록 179ms — name_search p50 418ms, docs/19 §6.1).
-- 부분 인덱스의 조건을 앱의 조건과 글자 그대로 같게 둔다 — 플래너가 질의의 조건이 인덱스 조건을 함의한다고 볼 수 있어야 쓴다.
--
-- pg_trgm 은 PostgreSQL 13 부터 신뢰된 확장(trusted)이라 DB 소유자(서비스 계정 product — k8s/90 · ops/04 · ops/25 의
-- CREATE DATABASE … OWNER product)가 만들 수 있다. 연산자 클래스는 public 에 두고 이름을 스키마까지 적는다(search_path 와 무관).
--
-- ROLLBACK-SAFE: 확장과 인덱스만 더한다. 옛 코드도 같은 질의라 그대로 돈다(더 빨라질 뿐). 쓰기는 인덱스를 만드는 동안만
-- 기다린다(운영 상품 수에서는 1초 안 · 데모 100만은 수십 초 — 트라이그램 GIN 이 가장 크다).
-- [JA] 生きている商品だけのインデックス二つ — カテゴリの件数 · 名前の部分一致検索。アプリの照会には常に status <> 'DELETED' が付き、
--      どのインデックスにも status がなく、件数がテーブルを読み直し(102ms)、名前検索は全件走査だった(178ms + 179ms)。

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

CREATE INDEX IF NOT EXISTS idx_product_category_live
    ON product.product (category_id, product_id)
    WHERE status <> 'DELETED';

CREATE INDEX IF NOT EXISTS idx_product_name_trgm
    ON product.product USING gin (lower(name) public.gin_trgm_ops)
    WHERE status <> 'DELETED';
