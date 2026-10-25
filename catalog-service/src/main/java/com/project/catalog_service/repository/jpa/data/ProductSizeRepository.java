package com.project.catalog_service.repository.jpa.data;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.project.catalog_service.model.ProductSize;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

@Repository
public interface ProductSizeRepository extends JpaRepository<ProductSize, Long> {

    // --- 낙관적 잠금 적용 ---
    //@Lock(LockModeType.OPTIMISTIC)
    /**
     * 이 SKU 에서 이 이름의 사이즈 — 같은 이름이 여럿이면 <b>첫 행</b>(size_id 가 가장 작은 것).
     *
     * <p>🐛 예전에는 {@code findBySkuSkuproductIdAndSize}(하나만 기대) 였다. 사이즈 없는 상품(식품 등)은 한 SKU 에 이름이 빈
     * 행이 여럿이라(시드 403 SKU 중 315) "결과가 둘 이상" 예외로 재고 예약·차감이 실패했다. 장바구니도 같은 이름의 첫 행을
     * 쓴다(CartService.sizeIndexOf) — 모두 같은 행을 가리킨다.
     */
    Optional<ProductSize> findFirstBySkuSkuproductIdAndSizeOrderBySizeIdAsc(Long skuId, String size);

    /**
     * 관리자 재고 목록 — 사이즈 id 커서(docs/53). 상품·색상을 함께 읽는다(줄마다 지연 로딩하지 않게).
     * {@code soldOutOnly} 면 0 이하만.
     */
    @org.springframework.data.jpa.repository.Query("""
            select s from ProductSize s
              join fetch s.sku k
              join fetch k.product p
              left join fetch k.color c
             where s.sizeId > :afterSizeId
               and (:soldOutOnly = false or s.quantity <= 0)
             order by s.sizeId
            """)
    java.util.List<ProductSize> findStockRows(
            @org.springframework.data.repository.query.Param("soldOutOnly") boolean soldOutOnly,
            @org.springframework.data.repository.query.Param("afterSizeId") Long afterSizeId,
            org.springframework.data.domain.Pageable page);

    /**
     * 상품들의 재고 표시에 쓰는 두 가지를 <b>한 질의</b>로 — 사이즈별 <b>지금</b> 수량과 옵션(색상 · 사이즈)별 살아 있는 예약 합
     * (docs/59 §5). 행은 {@code [kind, size_id, product_id, color_id, size, n]}: {@code kind = 'L'} 이면 {@code size_id} · {@code n}
     * (= 수량), {@code 'H'} 면 {@code product_id} · {@code color_id} · {@code size} · {@code n}(= 예약 합).
     *
     * <p>목록 DTO 는 캐시에서 온다 — 카테고리 목록 캐시({@code products}, 카테고리 키)는 만료가 없고 재고가 바뀌어도 비우지
     * 않는다. 그 수량으로 "품절" 을 그리면 언제 적 품절인지 모른다. 카드 한 페이지(수십 개)를 한 번에 읽는다.
     *
     * <p>🐛 2026-10-24 CPU 프로파일(docs/83 §5): 예전에는 이 둘을 따로 읽었다(지금 수량 · 예약 합 — 질의마다 제 트랜잭션 · DB 왕복).
     * 목록 · 상세 응답마다 두 번이라 catalog CPU 의 20% 가 재고 표시였다(Hibernate · 드라이버 · DB 쪽 TLS). 이제 {@code UNION ALL}
     * 하나 — 값은 두 질의를 합친 것과 같다({@code StockAvailabilityQueryTest} 가 예전 두 질의와 대조한다).
     *
     * <p>예약 쪽 {@code status = 'HELD'} 는 <b>리터럴</b>이다 — 부분 인덱스({@code WHERE status = 'HELD'})를 쓰려면 조건이 리터럴이어야
     * 한다({@code StockReservationRepository#sumHeldByProducts} 와 같은 이유).
     *
     * <p>[JA] 在庫表示に使う二つ(サイズごとの現在数量 · オプションごとの有効な予約の合計)を一つの照会で。以前は別々に読み(照会ごとに
     * トランザクション · DB 往復)、応答ごとに二回で catalog CPU の 20% だった。
     */
    @Query(value = """
            select 'L' as kind, s.size_id as size_id, cast(null as bigint) as product_id, cast(null as bigint) as color_id,
                   cast(null as varchar) as size, cast(s.quantity as bigint) as n
              from product.product_size s
              join product.product_sku k on k.skuproduct_id = s.skuproduct_id
             where k.product_id in (:productIds)
            union all
            select 'H', null, r.product_id, r.color_id, r.size, sum(r.qty)
              from product.stock_reservation r
             where r.product_id in (:productIds) and r.status = 'HELD' and r.expires_at > :now
             group by r.product_id, r.color_id, r.size
            """, nativeQuery = true)
    java.util.List<Object[]> liveAndHeld(@Param("productIds") java.util.Collection<Long> productIds,
            @Param("now") java.time.Instant now);

    /**
     * 상품들의 옵션(색상·사이즈)별 지금 수량과 상품 상태 — {@code [product_id, color_id, size, quantity, status]}. 장바구니
     * 화면이 담아 둔 항목의 재고를 새로 볼 때 쓴다(docs/59 §5). 장바구니 항목은 사이즈 순번이 아니라 사이즈 이름·색상 id 를
     * 들고 있고, 예약도 그 키다. 판매 종료(DELETED) 상품도 행이 나온다 — 엔티티의 @SQLRestriction 을 타지 않으므로.
     */
    @Query(value = """
            select k.product_id, k.color_id, s.size, s.quantity, p.status
              from product.product_size s
              join product.product_sku k on k.skuproduct_id = s.skuproduct_id
              join product.product p on p.product_id = k.product_id
             where k.product_id in (:productIds)
            """, nativeQuery = true)
    java.util.List<Object[]> liveOptions(@Param("productIds") java.util.Collection<Long> productIds);

    /**
     * 비어 있는 사이즈 SKU 코드를 규칙대로 채운다 — 채운 행 수. 규칙은 V17 의 DB 함수에만 있다.
     * 상품 등록 · CSV 일괄 등록 뒤에 부른다. 이미 코드가 있는 행은 건드리지 않으므로 몇 번 불러도 같다.
     */
    @Query(value = "select product.assign_size_sku_codes()", nativeQuery = true)
    int assignSkuCodes();

    /** 스타일(색상 변형)의 사이즈별 SKU 코드 — {@code [size_id, sku_code]}. 등록 직후 메모리의 엔티티에 옮겨 적을 때. */
    @Query(value = "select s.size_id, s.sku_code from product.product_size s where s.skuproduct_id = :skuId",
            nativeQuery = true)
    java.util.List<Object[]> skuCodesOf(@Param("skuId") Long skuId);
}
