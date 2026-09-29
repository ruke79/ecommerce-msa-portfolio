package com.project.catalog_service.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.catalog_service.model.Product;
import com.project.catalog_service.util.CursorPagenation;

/**
 * 커서 페이징의 계약.
 *
 * <p>🐛 회귀 대상: <b>관리자 상품 목록이 카탈로그 전체를 한 번에 받았다.</b>
 * {@code GET /api/product/products} 는 이름과 달리 {@code warmUpProductCaches} 를 거쳐
 * 전 카테고리 × 전 서브카테고리를 돌며 모든 상품을 한 배열로 만들고, 그 결과를 Redis 키
 * 하나에 통째로 담았다.
 *
 * <p>커서 페이징 유틸은 <b>이미 있었다</b>({@link CursorPagenation}, {@link ProductResponse}).
 * 다만 이것을 쓰는 엔드포인트가 카테고리 필수였고, 프론트는 한 곳도 부르지 않았다.
 * 여기서 고정하는 것은 그 유틸의 경계 동작이다 — "다음이 있는가" 를 잘못 판정하면
 * 목록이 중간에 끊기거나 무한 루프가 된다.
 */
class ProductCursorPageTest {

    /** 실제 엔티티가 필요하다 — ProductResponse 가 nextCursor 로 productId 를 읽는다. */
    private static List<Product> products(int count) {
        // @SuperBuilder 의 builder() 는 와일드카드 타입을 돌려주므로 매핑 타입을 명시한다.
        return IntStream.rangeClosed(1, count)
                .<Product>mapToObj(i -> Product.builder()
                        .productId((long) (100 - i))    // 내림차순 id
                        .name("p" + i)
                        .build())
                .toList();
    }

    @Test
    @DisplayName("한 페이지를 딱 채우면 '더 없음'이다 — 서버는 size+1 을 읽는다")
    void exactlyOnePageMeansLastScroll() {
        // size=10 을 요청하면 서버는 11개를 읽는다. 10개만 왔다면 그것이 끝이다.
        CursorPagenation<Product> page = CursorPagenation.of(products(10), 10);

        assertThat(page.isLastScroll()).isTrue();
        assertThat(page.getCurrentScrollItems()).hasSize(10);
    }

    @Test
    @DisplayName("size+1 개가 오면 다음이 있다 — 여분 1개는 화면에 내보내지 않는다")
    void extraItemMeansMorePages() {
        CursorPagenation<Product> page = CursorPagenation.of(products(11), 10);

        assertThat(page.isLastScroll()).isFalse();
        // 여분까지 보여주면 페이지 경계에서 같은 상품이 두 번 나타난다.
        assertThat(page.getCurrentScrollItems()).hasSize(10);
    }

    @Test
    @DisplayName("마지막 페이지의 nextCursor 는 -1 이다 — 화면이 이 값으로 멈춘다")
    void lastPageReportsSentinelCursor() {
        ProductResponse response = ProductResponse.of(CursorPagenation.of(products(3), 10), 3);

        assertThat(response.getNextCursor()).isEqualTo(-1L);
        assertThat(response.getContents()).hasSize(3);
    }

    @Test
    @DisplayName("다음이 있으면 nextCursor 는 이번 묶음의 마지막 상품 id 다")
    void nextCursorIsLastItemOfCurrentScroll() {
        List<Product> raw = products(11);
        ProductResponse response = ProductResponse.of(CursorPagenation.of(raw, 10), 100);

        // 10번째 항목(인덱스 9)의 id. 다음 페이지는 이 id 보다 작은 것부터 가져온다.
        assertThat(response.getNextCursor()).isEqualTo(raw.get(9).getProductId());
        assertThat(response.getNextCursor()).isNotEqualTo(-1L);
    }

    @Test
    @DisplayName("빈 결과도 마지막 페이지다 — 빈 응답을 계속 다시 부르면 무한 루프가 된다")
    void emptyPageIsLast() {
        ProductResponse response = ProductResponse.of(CursorPagenation.of(List.of(), 10), 0);

        assertThat(response.getNextCursor()).isEqualTo(-1L);
        assertThat(response.getContents()).isEmpty();
    }
}
