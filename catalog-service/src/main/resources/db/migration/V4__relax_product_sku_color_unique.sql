-- product_sku.color_id 의 UNIQUE 제약을 해제한다 (docs/19 §5.1).
--
-- 배경: ProductSku.color 가 @OneToOne 이었다. JPA 규약상 @OneToOne 의 소유측 FK 에는
-- UNIQUE 가 붙으므로, 색상 행 하나를 오직 한 SKU 만 참조할 수 있었다. 결과는 두 가지였다:
--   ① 카탈로그 전체의 SKU 수가 색상 행 수로 상한이 걸린다.
--      시드 데이터가 SKU 509 = 색상 509 로 제약을 정확히 채우고 있어,
--      상품을 하나만 더 추가해도 'duplicate key value violates unique constraint' 로 막혔다.
--   ② 색상 필터 패싯(frontend/ColorsFilter.jsx)이 무의미해진다 — 색상 1개당 상품이 1개뿐이다.
--
-- 이 저장소의 나머지 코드는 일관되게 색상을 **공유 어휘**로 다루고 있었다:
--   · ProductSkuRepository.findColorsByProductId → select DISTINCT b.color
--   · ProductColorRepository.findByColor → Optional<ProductColor> (색상명이 행을 식별)
--   · ProductColor 안에 @OneToMany(mappedBy="color") 가 주석으로 남아 있다
-- 따라서 설계 판단이 아니라 매핑 오류로 판단해 엔티티를 @ManyToOne 으로 바로잡고,
-- 그에 맞춰 제약을 해제한다.
--
-- 안전성: 기존 데이터는 색상값이 전부 고유하므로 제약을 **푸는** 방향은 어떤 행도 위반하지
-- 않는다. 되돌려야 한다면 중복이 생기기 전까지는 UNIQUE 를 다시 걸 수 있다.
--
-- ⚠️ 제약 이름을 하드코딩할 수 없다. Hibernate 가 ddl-auto 로 만든 이름은
-- 'ukf0mvdc9aqf8t23p9di0hn9lpy' 처럼 스키마 내용에서 파생된 해시라 환경마다 다르다.
-- 그래서 이름이 아니라 **정의**(product_sku 의 color_id 단일 컬럼 UNIQUE)로 찾아 지운다.
--
-- ⚠️ 그리고 이 테이블은 마이그레이션이 만든 것이 아니라 **Hibernate ddl-auto** 가 만든다.
-- Spring 은 Flyway 를 EntityManagerFactory **보다 먼저** 돌리므로, 빈 DB(테스트 컨테이너·신규
-- 환경)에서는 이 시점에 product_sku 가 아직 없다. 실제로 가드 없이 실행했을 때
-- 'relation "product.product_sku" does not exist' 로 컨텍스트 로딩이 통째로 깨졌다.
-- 그런 DB 에는 애초에 지울 제약도 없으므로, 테이블이 있을 때만 손댄다.
-- 신규 스키마의 color_id 인덱스는 ProductSku 의 @Table(indexes=...) 가 만든다.
DO $$
DECLARE
    constraint_rec RECORD;
BEGIN
    IF to_regclass('product.product_sku') IS NULL THEN
        RAISE NOTICE 'product.product_sku not present yet — nothing to relax (fresh schema)';
        RETURN;
    END IF;

    FOR constraint_rec IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
        WHERE nsp.nspname = 'product'
          AND rel.relname = 'product_sku'
          AND con.contype = 'u'
          -- color_id 단일 컬럼에 걸린 UNIQUE 만. 복합 UNIQUE 는 의도된 것일 수 있으므로 건드리지 않는다.
          AND con.conkey = ARRAY[
                (SELECT attnum FROM pg_attribute
                  WHERE attrelid = rel.oid AND attname = 'color_id' AND NOT attisdropped)
              ]::smallint[]
    LOOP
        EXECUTE format('ALTER TABLE product.product_sku DROP CONSTRAINT %I', constraint_rec.conname);
        RAISE NOTICE 'dropped unique constraint % on product_sku(color_id)', constraint_rec.conname;
    END LOOP;

    -- UNIQUE 제약은 인덱스를 겸하고 있었다. 제약을 지우면 그 인덱스도 사라지므로,
    -- color_id 로 조인하는 쿼리(findColorsByProductId 등)를 위해 일반 인덱스를 다시 만든다.
    -- 이게 없으면 SKU 가 늘어날수록 색상 조인이 Seq Scan 으로 떨어진다.
    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_product_sku_color_id ON product.product_sku (color_id)';
END $$;
