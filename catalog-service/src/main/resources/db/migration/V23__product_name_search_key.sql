-- 상품 이름 검색을 NFKC 로 — 전각 · 반각으로 친 검색어가 원문 그대로 저장된 이름을 찾는다.
--
-- 예전 비교는 lower(name) LIKE lower('%검색어%') 였다. 일본어 IME 가 내는 전각 영숫자(`ＴＳＨＩＲＴ`) · 반각 카나(`ｼｬﾂ`)는
-- 반각 · 전각으로 저장된 상품과 글자가 달라 하나도 맞지 않았다. 이름은 바꾸지 않는다(보여 주는 글 — NFKC 는 `①` 를 `1` 로 바꾼다).
-- 비교에만 이 함수를 양쪽에 건다: product.search_key(name) LIKE '%' || product.search_key(검색어) || '%'.
-- normalize(…, NFKC) · lower 는 IMMUTABLE 이라 식 인덱스가 된다(DB 인코딩 UTF8 — 운영 · 데모 모두).
--
-- ROLLBACK-SAFE: 함수와 인덱스를 더하기만 한다. 옛 코드는 V21 의 idx_product_name_trgm(lower(name))을 그대로 쓴다 — 그 인덱스는
-- 다음 contract 릴리스에서 지운다. 인덱스 생성 동안 상품 쓰기만 기다린다(운영 상품 수에서 1초 안 · 데모 100만은 수십 초 —
-- 기동 프로브 5분 안).
-- [JA] 商品名の検索を NFKC で — 全角 · 半角の検索語が原文のまま保存された名前を見つける。名前は変えず、比較にだけこの関数を両側に掛ける。
--      関数とインデックスを足すだけ(旧コードは V21 のインデックスを使う — 次の contract リリースで消す)。

CREATE OR REPLACE FUNCTION product.search_key(value text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    AS $$ SELECT lower(normalize(value, NFKC)) $$;

CREATE INDEX IF NOT EXISTS idx_product_name_search_key_trgm
    ON product.product USING gin (product.search_key(name) public.gin_trgm_ops)
    WHERE status <> 'DELETED';
