package com.project.catalog_service.repository.jpa.data;

import java.math.BigDecimal;
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

        /**
         * 가격·사이즈·색상으로 상품 id 를 추린다.
         *
         * <h3>🐛 한쪽만 준 가격 범위가 <b>범위 전체를 무시하게</b> 했다</h3>
         *
         * <p>예전 조건은 이랬다:
         *
         * <pre>{@code (:low_price is null or :high_price is null) or b.price between :low_price and :high_price}</pre>
         *
         * <p>즉 <b>둘 중 하나라도 null 이면 가격 조건이 통째로 사라진다.</b> 화면의 "최소만
         * 입력" · "500 이상" 은 상한이 없는 질의인데, 그때 가격 필터가 없는 것과 같아진다.
         *
         * <p>게다가 프론트가 빈 상한을 {@code 0} 으로 보내고 있어서(browse.jsx 의
         * {@code Number("") || 0}) 실제로는 {@code between 500 and 0} 이 됐다 — 아무것도
         * 맞지 않는다. 그리고 그 "아무것도 없음" 이 호출부에서 다시 <b>전체 상품</b>으로
         * 뒤집혔다(SearchService 1번 조건). 사용자가 본 것은 "500 이상을 눌렀더니 전부 나온다"다.
         *
         * <p>두 경계를 <b>따로</b> 본다. 둘 다 null 이면 조건이 없는 것과 같아 기존 동작
         * 그대로다.
         */
        @Query(value = "select distinct product_id from product.product_sku a " +
                        "left join product.product_size b on a.skuproduct_id = b.skuproduct_id " +
                        "left join product.product_color c on a.color_id = c.color_id " +
                        "where (:low_price is null or b.price >= :low_price) " +
                        "and (:high_price is null or b.price <= :high_price) " +
                        "and (:size is null or b.size ~* :size) " +
                        "and (:color is null or c.color ~* :color)", nativeQuery = true)
        List<Long> findProductIDBySizeAndPriceAndColor(@Param("low_price") BigDecimal lowPrice,
                        @Param("high_price") BigDecimal highPrice,
                        @Param("size") String size, @Param("color") String color);

        /**
         * 가격 필터의 후보 — 상품 id·사이즈의 <b>정가</b>·SKU 의 상시 할인율. 최종 판정은 {@code SearchService} 가
         * <b>화면에 보이는 가격</b>(기획전까지 합친 할인가)으로 한다(docs/40 §22).
         *
         * <p>🐛 예전에는 정가로 걸렀다 — 카드가 $70(정가 $100, 30% 할인)을 보여 주는데 "$80 이하" 에서 빠졌다. 기획전
         * 할인은 메모리의 맵이라(EventPricing) SQL 이 알 수 없다. 그래서 SQL 은 <b>언제나 참인 조건</b>만 건다: 할인가는
         * 정가를 넘지 않으므로 "할인가 ≥ 하한" 이면 "정가 ≥ 하한" 이다. 상한은 할인율을 알아야 해서 여기서 걸지 않는다.
         */
        @Query(value = "select a.product_id, b.price, a.discount from product.product_sku a " +
                        "join product.product_size b on a.skuproduct_id = b.skuproduct_id " +
                        "left join product.product_color c on a.color_id = c.color_id " +
                        "where (:low_price is null or b.price >= :low_price) " +
                        "and (:size is null or b.size ~* :size) " +
                        "and (:color is null or c.color ~* :color)", nativeQuery = true)
        List<Object[]> findPriceCandidates(@Param("low_price") BigDecimal lowPrice,
                        @Param("size") String size, @Param("color") String color);

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
         * 🐛 <b>카테고리를 고르면 검색이 500 이었다.</b>
         *
         * <p>예전 SQL 은 {@code IN (SELECT unnest(CAST(:productIds AS BIGINT[])))} 였다. 그런데
         * Hibernate 는 컬렉션 파라미터를 {@code (?, ?)} 로 펼치므로 PostgreSQL 은
         * {@code CAST((1, 2) AS BIGINT[])} 를 받는다 — "cannot cast type record to bigint[]".
         * 상품이 하나뿐이면 {@code CAST(1 AS BIGINT[])} 라 역시 실패한다. 빈 DB 에서는
         * 목록이 비어 통과하므로 컨테이너 기동 테스트가 잡지 못했고, 운영에서 카테고리에
         * 상품이 생기는 순간 터졌다.
         *
         * <p>{@code in (:productIds)} 면 Hibernate 의 확장이 곧 올바른 SQL 이다. 빈 목록은
         * 호출부가 미리 거른다({@code SearchService.getColors}) — {@code in ()} 은 SQL 오류다.
         * "전체" 는 {@link #findAllColors()} 다: 예전에는 {@code null} 을 넘겨 {@code unnest(NULL)}
         * 이 됐고, 그건 <b>빈 목록</b>이라 카테고리 없는 화면의 색상 메뉴가 늘 비어 있었다.
         */
        @Query(value = "select distinct b.color from product.product_sku a " +
                        "inner join product.product_color b on a.color_id = b.color_id " +
                        "where a.product_id in (:productIds)", nativeQuery = true)
        List<String> findColorsByProductId(@Param("productIds") java.util.Collection<Long> productIds);

        @Query(value = "select distinct b.color from product.product_sku a " +
                        "inner join product.product_color b on a.color_id = b.color_id", nativeQuery = true)
        List<String> findAllColors();

        // @Query(value = "select distinct b.size from product.product_sku a " +
        // "inner join product.product_size b on a.skuproduct_id = b.skuproduct_id " +
        // "where a.product_id in :productIds and b.size <> '' and b.size is not null",
        // nativeQuery = true)
        /**
         * 특정 상품 ID 목록에 해당하는 모든 SKU의 사이즈를 중복 없이 조회합니다.
         * JPQL을 사용하여 엔티티 간의 관계를 탐색합니다.
         * 
         * @param productIds 조회할 상품 ID 목록
         * @return 해당 상품들의 모든 사이즈 목록
         */
        // @Query("SELECT DISTINCT ps.size " +
        // "FROM Product p " +
        // "JOIN p.skus psg " + // Product(p)와 ProductSku(psg) 조인
        // "JOIN psg.sizes ps " + // ProductSku(psg)와 ProductSize(ps) 조인
        // "WHERE p.productId IN :productIds ")
        /** {@link #findColorsByProductId} 와 같은 이유로 {@code in (:productIds)} 다. 빈 목록은 호출부가 거른다. */
        @Query(value = "SELECT DISTINCT b.size FROM product.product_sku a " +
                        "INNER JOIN product.product_size b ON a.skuproduct_id = b.skuproduct_id " +
                        "WHERE a.product_id in (:productIds)", nativeQuery = true)
        List<String> findSizesByProductId(@Param("productIds") java.util.Collection<Long> productIds);

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
