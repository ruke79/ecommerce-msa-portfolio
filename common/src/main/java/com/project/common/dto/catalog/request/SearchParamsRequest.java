package com.project.common.dto.catalog.request;

import java.io.Serializable;
import java.math.BigDecimal;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@ToString
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) 
public class SearchParamsRequest implements Serializable{

    private String search;

    /**
     * 검색어는 받을 때 NFKC 로 맞춘다 — 전각 `ＴＳＨＩＲＴ` · 반각 `ｼｬﾂ` 가 `TSHIRT` · `シャツ` 와 같은 검색이 된다(캐시 키 · ES · DB 모두).
     * 상품 이름 쪽은 DB 의 {@code product.search_key}(catalog V23)가 같은 NFKC 로 비교한다.
     * [JA] 検索語は受け取り時に NFKC で揃える — 全角 · 半角カナが同じ検索になる(キャッシュキー · ES · DB すべて)。
     */
    public void setSearch(String search) {
        this.search = com.project.common.text.Nfkc.searchKey(search);
    }

    private String category;

    /**
     * 하위 카테고리 id.
     *
     * <h3>🐛 이 필드가 <b>없었다</b></h3>
     *
     * <p>화면에는 하위 카테고리 목록이 <b>그려지고 있었다</b>(서버가 {@code subCategories} 를
     * 함께 내려 준다). 그런데 그 항목에는 {@code onClick} 이 없었고 — 유일한 핸들러가
     * 주석 처리돼 있었다 — URL 파라미터도, 이 필드도, 검색 조건도 없었다. 즉 <b>목록만
     * 있고 기능은 어디에도 없었다.</b> 눌러도 아무 반응이 없던 이유다.
     *
     * <p>{@code category} 와 <b>함께</b> 쓸 수 있다(AND). 상위를 고르고 그 안에서 좁히는
     * 것이 화면의 모양이기 때문이다.
     */
    private String subCategory;

    private String style;

    private String size;

    private String color;

    private String brand;

    private String material;

    private String gender;

    private BigDecimal lowPrice;

    private BigDecimal highPrice;

    private BigDecimal shipping;

    private float rating;

    private int page;

    private int pageSize;

    /**
     * 필터 메뉴 데이터(카테고리·하위 카테고리·색상·사이즈·상세·브랜드)를 함께 줄지.
     *
     * <p>🐛 예전에는 선택지가 없었다 — {@code /api/search} 는 <b>매 요청마다</b> 그것들을
     * 함께 실어 보냈다. 카테고리 전체와 하위 카테고리 전체를 {@code findAll()} 로 읽어서다.
     *
     * <p>화면은 <b>첫 페이지 것만 쓰고 나머지는 버린다</b>(browse.jsx 가 `pages[0]` 에서만
     * 필터를 읽는다). 무한 스크롤을 붙인 뒤로는 2·3·4페이지를 받을 때마다 쓰지도 않을
     * 카테고리 전체가 따라왔다.
     *
     * <p>기본값 {@code true} 는 <b>기존 동작을 그대로 두기 위해서</b>다. 이 필드를 모르는
     * 호출부(있을 수 있다)는 예전과 똑같이 받는다. 필요 없는 쪽만 false 를 보낸다.
     */
    private boolean includeFilters = true;
}
