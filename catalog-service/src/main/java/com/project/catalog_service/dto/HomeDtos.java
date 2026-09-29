package com.project.catalog_service.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.project.catalog_service.model.HomeSection;
import com.project.common.dto.ProductDto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 홈 화면 섹션의 입출력.
 *
 * <p><b>공개 응답과 관리자 응답을 나눈다.</b> 공개 응답에 {@code published} 나
 * {@code startsAt} 을 실으면 아직 시작하지 않은 기획전의 존재와 시작 시각이 새어 나간다.
 * 화면이 그리는 데 필요한 것만 내려보낸다.
 */
public final class HomeDtos {

    private HomeDtos() {
    }

    /**
     * 공개 응답의 섹션 하나. <b>상품이 이미 그룹핑되어 들어 있다.</b>
     *
     * <p>예전에는 서버가 모든 카테고리의 상품을 한 배열로 평탄화해 내려보내고, 프론트가
     * {@code products.filter(p => p.category.name === category)} 로 다시 나눴다 — 서버가
     * 이미 아는 구분을 클라이언트가 다시 하는 셈이고, 응답 크기도 카테고리 수에 비례해
     * 커졌다.
     *
     * <p>{@code categoryId}·{@code subcategoryId} 는 카드의 "더 보기"가 갈 곳이다
     * ({@code /browse?category=&subCategory=}). 예전에는 이 둘이 없어서 "더 보기"가 링크가 아닌
     * 글자였다 — 눌러도 아무 일도 없었다. 탐색 화면은 <b>이름이 아니라 id</b> 로 거르므로
     * 서버가 이름을 id 로 바꿔 준다. TSID 라 문자열로 싣는다(JS 숫자는 2^53 에서 잘린다).
     * 찾지 못하면 null — 프론트는 그때 전체 목록으로 보낸다.
     */
    public record HomeSectionView(Long id, String title, String layout,
                                  String categoryId, String subcategoryId,
                                  List<ProductDto> products) {

        /** 상품 목록만 바꾼 사본(재고 반영 등). 링크 정보는 그대로 둔다. */
        public HomeSectionView withProducts(List<ProductDto> replaced) {
            return new HomeSectionView(id, title, layout, categoryId, subcategoryId, replaced);
        }
    }

    /** 홈 전체. 섹션 목록 하나로 첫 화면이 완성된다. */
    public record HomeView(List<HomeSectionView> sections) {
    }

    /**
     * 관리자 응답.
     *
     * <p>{@code categoryExists} 와 {@code visibleNow} 가 핵심이다 — "왜 홈에 안 나오지"의
     * 답이 대개 이 둘이다(카테고리 이름이 바뀌었거나, 기간 밖이거나).
     */
    public record HomeSectionAdminView(Long id, String title, String kind, String categoryName,
                                       String subcategoryName, String layout, int itemLimit,
                                       int sortOrder, boolean published,
                                       LocalDateTime startsAt, LocalDateTime endsAt,
                                       boolean visibleNow, boolean categoryExists) {

        public static HomeSectionAdminView of(HomeSection s, LocalDateTime now, boolean categoryExists) {
            return new HomeSectionAdminView(s.getSectionId(), s.getTitle(), s.getKind(),
                    s.getCategoryName(), s.getSubcategoryName(), s.getLayout(), s.getItemLimit(),
                    s.getSortOrder(), s.isPublished(), s.getStartsAt(), s.getEndsAt(),
                    s.isVisibleAt(now), categoryExists);
        }
    }

    /**
     * 생성·수정 요청.
     *
     * <p>{@code kind} 와 {@code layout} 을 정규식으로 막는 이유: 오타가 나면 그 섹션만
     * 조용히 빠진다(어느 분기에도 걸리지 않는다). 저장 시점에 걸러야 한다.
     */
    public record HomeSectionRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Pattern(regexp = "CATEGORY|SUBCATEGORY") String kind,
            @NotBlank @Size(max = 100) String categoryName,
            @Size(max = 100) String subcategoryName,
            @NotBlank @Pattern(regexp = "GRID|SWIPER") String layout,
            // 상한을 두는 이유: 홈은 첫 화면이다. 실수로 1000 을 넣으면 그 한 섹션이
            // 첫 로딩을 통째로 늦춘다.
            @Min(1) @Max(50) Integer itemLimit,
            Integer sortOrder,
            Boolean published,
            LocalDateTime startsAt,
            LocalDateTime endsAt) {
    }
}
