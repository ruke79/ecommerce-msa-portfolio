package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.project.catalog_service.dto.HomeDtos.HomeSectionRequest;
import com.project.catalog_service.dto.HomeDtos.HomeView;
import com.project.catalog_service.model.Category;
import com.project.catalog_service.model.HomeSection;
import com.project.catalog_service.model.Subcategory;
import com.project.catalog_service.repository.jpa.data.CategoryRepository;
import com.project.catalog_service.repository.jpa.data.HomeSectionRepository;
import com.project.common.dto.ProductDto;

/**
 * 홈 섹션의 <b>판단</b>을 고정한다.
 *
 * <p>첫 화면은 사이트의 얼굴이라, 여기서 가장 중요한 것은 "그린다"가 아니라 <b>"하나가
 * 잘못돼도 전체가 죽지 않는다"</b>이다 — 카테고리 하나가 지워졌다고 홈이 빈 페이지가
 * 되면 안 된다.
 *
 * <p>{@code HomeSectionRepository.findVisible} 의 JPQL 은 여기서 검증되지 않는다(실제 DB가
 * 필요하다). 그 쿼리와 {@link HomeSection#isVisibleAt} 은 같은 규칙을 두 번 적는 자리라,
 * 경계 테스트가 그 정의를 고정한다.
 */
@DisplayName("홈 섹션")
class HomeSectionServiceTest {

    private HomeSectionRepository sectionRepository;
    private CategoryRepository categoryRepository;
    private ProductService productService;
    private HomeSectionService service;

    @BeforeEach
    void setUp() {
        sectionRepository = mock(HomeSectionRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        productService = mock(ProductService.class);
        service = new HomeSectionService(sectionRepository, categoryRepository, productService);
    }

    private static HomeSection section(long id, String category, String layout, int limit) {
        return HomeSection.builder()
                .sectionId(id).title(category).kind("CATEGORY")
                .categoryName(category).layout(layout).itemLimit(limit)
                .sortOrder((int) id).published(true)
                .build();
    }

    private static ProductDto product() {
        return ProductDto.builder().id("1").name("상품").build();
    }

    private static Subcategory subcategory(long id, String name) {
        Subcategory sc = mock(Subcategory.class);
        when(sc.getSubcategoryId()).thenReturn(id);
        when(sc.getSubcategoryName()).thenReturn(name);
        return sc;
    }

    private void givenCategoryExists(String name) {
        when(categoryRepository.findByCategoryName(name))
                .thenReturn(Optional.of(mock(Category.class)));
    }

    @Nested
    @DisplayName("공개 조회")
    class PublicHome {

        @Test
        @DisplayName("섹션마다 상품이 그룹핑된 채로 내려간다 — 프론트가 다시 나누지 않는다")
        void sectionsCarryTheirOwnProducts() {
            when(sectionRepository.findVisible(any()))
                    .thenReturn(List.of(section(1, "fashion", "GRID", 4)));
            when(productService.getProductsByCategory(eq("fashion"), anyLong(), anyInt()))
                    .thenReturn(List.of(product(), product()));

            HomeView home = service.home();

            assertThat(home.sections()).hasSize(1);
            assertThat(home.sections().get(0).title()).isEqualTo("fashion");
            assertThat(home.sections().get(0).layout()).isEqualTo("GRID");
            assertThat(home.sections().get(0).products()).hasSize(2);
        }

        @Test
        @DisplayName("섹션의 itemLimit 이 그대로 조회 개수가 된다")
        void itemLimitReachesTheQuery() {
            when(sectionRepository.findVisible(any()))
                    .thenReturn(List.of(section(1, "books", "SWIPER", 7)));
            when(productService.getProductsByCategory(anyString(), anyLong(), anyInt()))
                    .thenReturn(List.of(product()));

            service.home();

            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(productService).getProductsByCategory(eq("books"), anyLong(), limit.capture());
            assertThat(limit.getValue()).isEqualTo(7);
        }

        @Test
        @DisplayName("한 섹션이 터져도 나머지 홈은 그대로 뜬다 — 첫 화면이 빈 페이지가 되면 안 된다")
        void oneBrokenSectionDoesNotKillTheHome() {
            when(sectionRepository.findVisible(any()))
                    .thenReturn(List.of(section(1, "gone", "GRID", 4), section(2, "books", "SWIPER", 4)));
            when(productService.getProductsByCategory(eq("gone"), anyLong(), anyInt()))
                    .thenThrow(new IllegalStateException("카테고리가 사라졌다"));
            when(productService.getProductsByCategory(eq("books"), anyLong(), anyInt()))
                    .thenReturn(List.of(product()));

            HomeView home = service.home();

            assertThat(home.sections()).hasSize(1);
            assertThat(home.sections().get(0).title()).isEqualTo("books");
        }

        @Test
        @DisplayName("상품이 없는 섹션은 빠진다 — 제목만 있고 아래가 빈 줄은 고장처럼 보인다")
        void emptySectionsAreDropped() {
            when(sectionRepository.findVisible(any()))
                    .thenReturn(List.of(section(1, "fashion", "GRID", 4)));
            when(productService.getProductsByCategory(anyString(), anyLong(), anyInt()))
                    .thenReturn(List.of());

            assertThat(service.home().sections()).isEmpty();
        }

        @Test
        @DisplayName("서브카테고리 섹션은 서브카테고리 조회를 쓴다")
        void subcategorySectionUsesTheSubcategoryQuery() {
            HomeSection s = section(1, "fashion", "SWIPER", 5);
            s.setKind("SUBCATEGORY");
            s.setSubcategoryName("shoes");
            when(sectionRepository.findVisible(any())).thenReturn(List.of(s));
            when(productService.getProductsByCategoryAndSubcategory(
                    anyString(), anyString(), anyInt(), anyInt())).thenReturn(List.of(product()));

            service.home();

            verify(productService).getProductsByCategoryAndSubcategory("fashion", "shoes", 0, 5);
            verify(productService, never()).getProductsByCategory(anyString(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("🐛 '더 보기'가 갈 곳 — 카테고리 이름을 탐색 화면의 id(문자열)로 바꿔 싣는다")
        void categorySectionCarriesItsBrowseId() {
            when(sectionRepository.findVisible(any()))
                    .thenReturn(List.of(section(1, "fashion", "GRID", 4)));
            when(productService.getProductsByCategory(eq("fashion"), anyLong(), anyInt()))
                    .thenReturn(List.of(product()));
            when(categoryRepository.findIdByCategoryName("fashion")).thenReturn(812345678901234567L);

            var view = service.home().sections().get(0);

            // TSID 는 2^53 을 넘는다 — 숫자로 실으면 브라우저에서 끝자리가 바뀐다.
            assertThat(view.categoryId()).isEqualTo("812345678901234567");
            assertThat(view.subcategoryId()).isNull();
        }

        @Test
        @DisplayName("서브카테고리 섹션은 서브카테고리 id 까지 싣는다 — 같은 카테고리 안에서 이름으로 찾는다")
        void subcategorySectionCarriesBothIds() {
            HomeSection s = section(1, "fashion", "SWIPER", 5);
            s.setKind("SUBCATEGORY");
            s.setSubcategoryName("shoes");
            when(sectionRepository.findVisible(any())).thenReturn(List.of(s));
            when(productService.getProductsByCategoryAndSubcategory(
                    anyString(), anyString(), anyInt(), anyInt())).thenReturn(List.of(product()));
            when(categoryRepository.findIdByCategoryName("fashion")).thenReturn(10L);
            List<Subcategory> subs = List.of(subcategory(21L, "bags"), subcategory(22L, "shoes"));
            when(categoryRepository.findSubCategoriesByCategoryId(10L)).thenReturn(subs);

            var view = service.home().sections().get(0);

            assertThat(view.categoryId()).isEqualTo("10");
            assertThat(view.subcategoryId()).isEqualTo("22");
        }

        @Test
        @DisplayName("링크를 못 만들어도 섹션은 뜬다 — 링크 실패가 상품까지 지우면 안 된다")
        void linkFailureKeepsTheSection() {
            when(sectionRepository.findVisible(any()))
                    .thenReturn(List.of(section(1, "fashion", "GRID", 4)));
            when(productService.getProductsByCategory(eq("fashion"), anyLong(), anyInt()))
                    .thenReturn(List.of(product()));
            when(categoryRepository.findIdByCategoryName("fashion"))
                    .thenThrow(new IllegalStateException("DB 끊김"));

            var sections = service.home().sections();

            assertThat(sections).hasSize(1);
            assertThat(sections.get(0).categoryId()).isNull();
            assertThat(sections.get(0).products()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("노출 판정")
    class Visibility {

        private final LocalDateTime now = LocalDateTime.of(2026, 9, 13, 12, 0);

        private HomeSection withWindow(boolean published, LocalDateTime from, LocalDateTime to) {
            HomeSection s = section(1, "fashion", "GRID", 4);
            s.setPublished(published);
            s.setStartsAt(from);
            s.setEndsAt(to);
            return s;
        }

        @Test
        @DisplayName("비공개면 기간과 무관하게 안 보인다")
        void unpublishedIsHidden() {
            assertThat(withWindow(false, null, null).isVisibleAt(now)).isFalse();
            assertThat(withWindow(false, now.minusDays(1), now.plusDays(1)).isVisibleAt(now)).isFalse();
        }

        @Test
        @DisplayName("기간이 없으면 공개 여부만 본다")
        void noWindowMeansAlways() {
            assertThat(withWindow(true, null, null).isVisibleAt(now)).isTrue();
        }

        @Test
        @DisplayName("기간 밖이면 안 보인다 — 경계는 양쪽 다 포함")
        void outsideTheWindowIsHidden() {
            assertThat(withWindow(true, now.plusSeconds(1), null).isVisibleAt(now)).isFalse();
            assertThat(withWindow(true, null, now.minusSeconds(1)).isVisibleAt(now)).isFalse();
            assertThat(withWindow(true, now, now).isVisibleAt(now)).isTrue();
        }
    }

    @Nested
    @DisplayName("관리")
    class Admin {

        @Test
        @DisplayName("없는 카테고리를 가리키는 섹션은 저장되지 않는다 — 증상이 '그 섹션만 안 나온다'로 숨는다")
        void unknownCategoryIsRejected() {
            when(categoryRepository.findByCategoryName("오타")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(new HomeSectionRequest(
                    "제목", "CATEGORY", "오타", null, "GRID", 4, 0, true, null, null)))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(sectionRepository, never()).save(any(HomeSection.class));
        }

        @Test
        @DisplayName("기본은 비공개다 — 만들자마자 첫 화면에 뜨면 작업 중인 섹션이 노출된다")
        void createdSectionsAreDraftByDefault() {
            givenCategoryExists("fashion");
            when(sectionRepository.save(any(HomeSection.class))).thenAnswer(i -> i.getArgument(0));

            var view = service.create(new HomeSectionRequest(
                    "제목", "CATEGORY", "fashion", null, "GRID", 4, 0, null, null, null));

            assertThat(view.published()).isFalse();
        }

        @Test
        @DisplayName("목록은 '지금 보이는가'와 '카테고리가 있는가'를 함께 준다 — 안 나오는 이유가 대개 이 둘이다")
        void adminListExplainsWhyASectionIsMissing() {
            HomeSection alive = section(1, "fashion", "GRID", 4);
            HomeSection orphan = section(2, "사라진카테고리", "GRID", 4);
            orphan.setPublished(false);
            when(sectionRepository.findAllByOrderBySortOrderAscSectionIdAsc())
                    .thenReturn(List.of(alive, orphan));
            givenCategoryExists("fashion");
            when(categoryRepository.findByCategoryName("사라진카테고리")).thenReturn(Optional.empty());

            var all = service.all();

            assertThat(all).hasSize(2);
            assertThat(all.get(0).categoryExists()).isTrue();
            assertThat(all.get(0).visibleNow()).isTrue();
            assertThat(all.get(1).categoryExists()).isFalse();
            assertThat(all.get(1).visibleNow()).isFalse();
        }
    }
}
