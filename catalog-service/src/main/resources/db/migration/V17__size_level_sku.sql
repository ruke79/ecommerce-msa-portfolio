-- SKU 를 사이즈 단위로 — product_size 한 줄마다 고유 SKU 코드(sku_code).
--
-- SKU(Stock Keeping Unit)는 "창고에서 따로 세야 하는 단위"다. 이 스키마에서 수량(quantity)과
-- 가격(price)을 가진 것은 product_size 한 줄이다 — 그런데 SKU 코드는 한 단계 위인
-- product_sku(= 색상 변형)에 붙어 있었다. 검정 270 과 검정 280 은 재고가 따로인데 코드가 같았다.
-- 그래서 주문 품목에 "무엇이 나갔나" 를 코드 하나로 적을 수 없었다(WMS · 바코드 연동의 전제).
--
-- 이제 두 단계를 나눠 부른다.
--   · product_sku.sku        → 스타일(색상) 코드. 기존 값 그대로 — 바꾸지 않는다.
--   · product_size.sku_code  → 진짜 SKU. "<스타일 코드>-<사이즈>" (예: FAS-100042-XL)
--
-- 규칙은 아래 함수 **한 곳**에만 둔다. 기존 행 백필(이 파일), 관리자 등록(ProductService),
-- CSV 일괄 등록(BatchConfig 의 마지막 단계)이 모두 이 함수를 부른다 — 같은 규칙을 Java 와 SQL 에
-- 두 번 적으면 언젠가 어긋난다.
--
-- 추가만 한다(컬럼 · 함수 · 인덱스). 옛 이미지로 되돌려도 옛 코드는 이 컬럼을 모르고 NULL 로 두므로
-- 문제없다 — 다음 배포 뒤 함수가 채운다.

ALTER TABLE product.product_size ADD COLUMN IF NOT EXISTS sku_code varchar(96);

-- 비어 있는 sku_code 를 규칙대로 채운다. 채운 행 수를 돌려준다. 이미 코드가 있는 행은 건드리지 않는다 —
-- SKU 는 한 번 정하면 바꾸지 않는다(과거 주문 · 반품 · 회계가 그 코드를 들고 있다).
--
-- 규칙
--   ① 사이즈 이름이 비어 있으면(사이즈 없는 상품 — 책 · 식품) 스타일 코드 그대로. 사이즈가 없는
--      상품은 스타일 하나가 곧 재고 단위다.
--   ② 아니면 이름을 대문자로, 영숫자가 아닌 연속 문자는 '-' 하나로, 양끝 '-' 는 떼어 붙인다.
--      "xl" → XL, "270 mm" → 270-MM
--   ③ ②의 결과가 비면(영숫자가 하나도 없는 이름 — "프리" 등) 스타일 안 순번으로: -S1, -S2 …
--      SKU 는 사람이 읽고 바코드에 찍는 값이라 ASCII 로 둔다.
--   ④ 같은 코드가 이미 있거나 이번에 둘 이상 나오면(같은 스타일에 같은 사이즈 이름이 두 줄 —
--      시드 CSV 에 실제로 있다) 첫 줄만 그 코드를 갖고 나머지는 "<코드>-dup-<size_id>".
--      "-2" 가 아닌 이유는 V7 과 같다 — 사이즈 "2" 와 겹친다. 소문자 "dup" 은 ②가 만들 수 없는
--      모양이라 다른 규칙의 결과와 재충돌하지 않는다.
--   ⑤ 부모(스타일)가 없거나 스타일 코드가 NULL 인 행은 만들 수 없으므로 그대로 둔다.
CREATE OR REPLACE FUNCTION product.assign_size_sku_codes() RETURNS integer
LANGUAGE plpgsql AS $$
DECLARE
    assigned integer;
BEGIN
    WITH sized AS (
        SELECT s.size_id,
               s.sku_code,
               k.sku AS style_code,
               btrim(coalesce(s.size, '')) AS raw_name,
               btrim(regexp_replace(upper(coalesce(s.size, '')), '[^A-Z0-9]+', '-', 'g'), '-') AS norm,
               row_number() OVER (PARTITION BY s.skuproduct_id ORDER BY s.size_id) AS pos
          FROM product.product_size s
          JOIN product.product_sku k ON k.skuproduct_id = s.skuproduct_id
         WHERE k.sku IS NOT NULL
    ), wanted AS (
        SELECT size_id,
               CASE
                   WHEN raw_name = '' THEN style_code
                   WHEN norm = ''     THEN style_code || '-S' || pos
                   ELSE style_code || '-' || norm
               END AS code
          FROM sized
         WHERE sku_code IS NULL
    ), ranked AS (
        SELECT w.size_id,
               w.code,
               row_number() OVER (PARTITION BY w.code ORDER BY w.size_id) AS rn,
               EXISTS (SELECT 1 FROM product.product_size e WHERE e.sku_code = w.code) AS taken
          FROM wanted w
    )
    UPDATE product.product_size p
       SET sku_code = CASE WHEN r.rn = 1 AND NOT r.taken THEN r.code
                           ELSE r.code || '-dup-' || r.size_id END
      FROM ranked r
     WHERE p.size_id = r.size_id;

    GET DIAGNOSTICS assigned = ROW_COUNT;
    RETURN assigned;
END;
$$;

SELECT product.assign_size_sku_codes();

CREATE UNIQUE INDEX IF NOT EXISTS uq_product_size_sku_code
    ON product.product_size (sku_code)
    WHERE sku_code IS NOT NULL;
