package com.project.catalog_service.repository.jpa.data;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.project.catalog_service.model.ProductSku;

import jakarta.persistence.LockModeType;

@Repository
public interface ProductSkuRepository extends JpaRepository<ProductSku, Long> {

        // ── SKU 자동 생성 (ProductService.generateSku) ───────────────────
        //
        // 번호는 DB 시퀀스에서 받는다(V7). 애플리케이션에서 max+1 을 세는 방식은
        // 레플리카 2개가 동시에 등록하면 같은 번호를 받는다 — 시퀀스는 그럴 수 없다.
        // 시퀀스는 롤백해도 번호를 되돌리지 않으므로 결번이 생기는데, 그것이 정상이다.
        // SKU 는 식별자이지 일련번호가 아니다 — 연속성을 약속하면 안 된다.
        @Query(value = "select nextval('product.product_sku_seq')", nativeQuery = true)
        Long nextSkuNumber();

        boolean existsBySku(String sku);

        // ── 재고·판매량 갱신용 SKU 조회 (ProductService.updateSoldValue) ──────────
        //
        // 예전에는 파생 쿼리 findByProductProductIdAndSizesSizeAndColorColorId 였다 — 컬렉션
        // sizes 를 **join** 하는 형태. 바꾼 이유는 이름 길이가 아니라 **잠금 범위**다.
        //
        // 원저자가 주석으로 남겨 둔 PESSIMISTIC_WRITE 를 켜야 한다. sold 는 읽고-더하고-쓰는
        // 값이라 같은 SKU 의 주문 둘이 겹치면 한쪽 증가분이 사라진다(BaseEntity 의 @Version
        // 이 잡아 주긴 하지만 그건 예외로 터뜨려 재전달을 유발하는 쪽이고, 카프카 소비
        // 경로에서는 행 하나를 잠가 직렬화하는 편이 맞다). 그런데 join 형태에 FOR UPDATE 를
        // 붙이면 PostgreSQL 은 FROM 에 있는 **두 테이블의 행을 모두** 잠근다 — 같은 사가의
        // 다음 단계인 updateProductSize_Qty(product_size 수량 차감)가 이 잠금에 막힌다.
        // 실측: join+FOR UPDATE 를 쥔 채 product_size UPDATE → lock timeout, exists → 통과.
        // exists 는 product_sku 한 테이블만 잠근다.
        //
        // 덤: product_size 에는 (skuproduct_id, size) 유니크 제약이 없어 같은 SKU 에 같은
        // 사이즈 행이 둘 있으면 join 은 DB 에서 두 행을 돌려준다. Hibernate 6 가 루트
        // 엔티티를 중복 제거해 주므로 예외까지 가지는 않지만(실측), exists 는 애초에 한 행이다.
        // (join 에 distinct 를 붙이는 길은 없다 — PostgreSQL 은 DISTINCT … FOR UPDATE 를
        // 거부한다.)
        //
        // 트랜잭션은 짧다(갱신 + 캐시 DTO 변환).
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("""
               select sk from ProductSku sk
                where sk.product.productId = :productId
                  and sk.color.colorId = :colorId
                  and exists (select 1 from ProductSize s
                               where s.sku = sk and s.size = :size)
               """)
        Optional<ProductSku> findForStockUpdate(@Param("productId") Long productId,
                        @Param("size") String size,
                        @Param("colorId") Long colorId);

        // 가격 · 사이즈 · 색상 필터는 SearchService.skuFilter 의 상관 서브쿼리(EXISTS)다 — 🐛 2026-10-19 까지는 여기의 두 질의
        // (findProductIDBySizeAndPriceAndColor · findPriceCandidates)가 후보를 카테고리와 상관없이 전부 앱으로 읽었다(100만 상품에서
        // 힙 부족, docs/83 §5). 두 경계를 따로 보는 규칙(한쪽만 준 가격 범위 — docs/40)은 skuFilter 가 그대로 지킨다.

        @Query(value = "select product_id from product.product_sku a " +
                        "left join product.product_size b on a.skuproduct_id = b.skuproduct_id " +
                        "left join product.product_color c on a.color_id = c.color_id " +
                        "where ((:low_price is null or :high_price is null ) or b.price between :low_price and :high_price) and (:size is null or b.size ~* :size)"
                        +
                        "and (:color is null or c.color ~* :color) " +
                        "order by a.sold desc", nativeQuery = true)
        List<Long> findProductIDBySizeAndPriceAndColorOrderBySoldDesc(@Param("low_price") Integer lowPrice,
                        @Param("high_price") Integer highPrice,
                        @Param("size") String size, @Param("color") String color);

        /**
         * 필터 메뉴의 색상 — 한 카테고리 상품들의 SKU 색상, 중복 없이. 상품 id 를 앱으로 가져오지 않고 DB 안에서 조인한다.
         *
         * <p>🐛 2026-10-19 데이터 규모 측정(docs/83 §5): 예전에는 카테고리의 상품 id 를 <b>전부</b> 읽어 {@code in (:productIds)} 로
         * 다시 보냈다 — Hibernate 가 목록을 바인드 변수로 펼쳐, 100만 상품에서 상품이 65,535 개를 넘는 카테고리(fashion · books …)는
         * {@code PSQLException: PreparedStatement can have at most 65,535 parameters} 로 그 검색이 매번 실패했다.
         * (그 전의 {@code unnest(CAST(:ids AS BIGINT[]))} 는 상품이 하나라도 있으면 실패했다 — "cannot cast type record to bigint[]".)
         * "전체" 는 {@link #findAllColors()}.
         * [JA] フィルターメニューの色 — 商品 id をアプリに持ち込まず DB 内で結合する。以前は id 全部を IN に展開し、65,535 を超えると失敗した。
         */
        @Query(value = "select distinct b.color from product.product_sku a " +
                        "inner join product.product_color b on a.color_id = b.color_id " +
                        "inner join product.product p on p.product_id = a.product_id " +
                        "where p.category_id = :categoryId", nativeQuery = true)
        List<String> findColorsByCategoryId(@Param("categoryId") Long categoryId);

        @Query(value = "select distinct b.color from product.product_sku a " +
                        "inner join product.product_color b on a.color_id = b.color_id", nativeQuery = true)
        List<String> findAllColors();

        /** 필터 메뉴의 사이즈 — {@link #findColorsByCategoryId} 와 같은 이유로 DB 안에서 조인한다. */
        @Query(value = "SELECT DISTINCT b.size FROM product.product_sku a " +
                        "INNER JOIN product.product_size b ON a.skuproduct_id = b.skuproduct_id " +
                        "INNER JOIN product.product p ON p.product_id = a.product_id " +
                        "WHERE p.category_id = :categoryId", nativeQuery = true)
        List<String> findSizesByCategoryId(@Param("categoryId") Long categoryId);

        @Query(value = "SELECT DISTINCT b.size FROM product.product_sku a " +
                        "INNER JOIN product.product_size b ON a.skuproduct_id = b.skuproduct_id", nativeQuery = true)
        List<String> findAllSizes();

        // Mysql
        // @Query(value = "select distinct product_id from product_sku a " +
        // "left join product_size b on a.skuproduct_id = b.skuproduct_id " +
        // "left join product_color c on a.color_id = c.color_id " +
        // "where ((:low_price is null or :high_price is null ) or b.price between
        // :low_price and :high_price) and (:size is null or b.size REGEXP :size)" +
        // "and (:color is null or c.color REGEXP :color)", nativeQuery = true)
        // List<Long> findProductIDBySizeAndPriceAndColor(@Param("low_price") BigDecimal
        // lowPrice, @Param("high_price") BigDecimal highPrice,
        // @Param("size") String size, @Param("color") String color);

        // List<ProductSku>
        // findBySizesPriceBetweenAndSizesSizeAndColorColor(@Param("low_price") Integer
        // lowPrice, @Param("high_price") Integer highPrice,
        // @Param("size") String size, @Param("color") String color);

        // @Query(value = "select product_id from product_sku a " +
        // "left join product_size b on a.skuproduct_id = b.skuproduct_id " +
        // "left join product_color c on a.color_id = c.color_id " +
        // "where ((:low_price is null or :high_price is null ) or b.price between
        // :low_price and :high_price) and (:size is null or b.size REGEXP :size)" +
        // "and (:color is null or c.color REGEXP :color) " +
        // "order by a.sold desc", nativeQuery = true)
        // List<Long>
        // findProductIDBySizeAndPriceAndColorOrderBySoldDesc(@Param("low_price")
        // Integer lowPrice, @Param("high_price") Integer highPrice,
        // @Param("size") String size, @Param("color") String color);

        // @Query(value = "select distinct b.color from product_sku a " +
        // "inner join product_color b " +
        // "where a.product_id in :productIds", nativeQuery = true)
        // List<String> findColorsByProductId(@Param("productIds") List<Long>
        // productIds);

        // @Query(value = "select distinct b.size from product_sku a " +
        // "inner join product_size b " +
        // "where a.product_id in :productIds and b.size <> '' and b.size is not null",
        // nativeQuery = true)
        // List<String> findSizesByProductId(@Param("productIds") List<Long>
        // productIds);

    /**
     * 이 트랜잭션 동안만 잠금 대기 상한을 둔다(SET LOCAL lock_timeout). 예약이 SKU 잠금을 호출자의 기한보다 오래 기다리지 않게 —
     * 기다림이 길어지면 PostgreSQL 이 55P03 으로 끊는다(StockReservationService.reserve).
     */
    @org.springframework.data.jpa.repository.Query(value = "select set_config('lock_timeout', :value, true)", nativeQuery = true)
    String setLocalLockTimeout(@org.springframework.data.repository.query.Param("value") String value);
}
