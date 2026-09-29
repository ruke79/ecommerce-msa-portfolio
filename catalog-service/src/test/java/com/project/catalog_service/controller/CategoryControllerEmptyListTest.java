package com.project.catalog_service.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.project.catalog_service.model.Subcategory;
import com.project.catalog_service.repository.jpa.data.CategoryRepository;
import com.project.catalog_service.repository.jpa.data.SubcategoryRepository;
import com.project.catalog_service.service.CategoryService;

/**
 * 목록 조회에서 <b>0건은 오류가 아니다.</b>
 *
 * <p>두 엔드포인트가 결과가 비면 400 을 돌려주고 있었다:
 *
 * <pre>
 *   if (!subcategories.isEmpty()) { ... 200 ... }
 *   else return new ResponseEntity&lt;&gt;(SUBCATEGORY_NOT_FOUND, HttpStatus.BAD_REQUEST);
 * </pre>
 *
 * <p>400 은 "당신의 요청이 잘못됐다" 는 뜻인데 요청은 완벽히 정상이었다. 클라이언트가
 * 고칠 것이 없으므로 화면은 그저 실패 토스트를 띄울 수밖에 없다.
 *
 * <p>그리고 이것이 <b>갓 설치한 시스템을 잠갔다.</b> 관리자가 하위 카테고리를 만들려면
 * /admin/category 를 열어야 하는데, 그 화면이 열리면서 이 API 를 부르고 400 을 받아
 * "Faild to load subcategories" 로 끝난다 — <b>하나도 없어서 하나도 만들 수 없는</b>
 * 닭과 달걀이다. 빈 DB 에서 실제로 그 상태가 됐다.
 *
 * <p>빈 목록을 "없음" 으로 다루는 것은 화면의 일이다. 서버는 200 과 {@code []} 를 준다.
 */
class CategoryControllerEmptyListTest {

    private final CategoryRepository categoryRepository = mock(CategoryRepository.class);
    private final SubcategoryRepository subCategoryRepository = mock(SubcategoryRepository.class);
    private final CategoryService categoryService = mock(CategoryService.class);

    private final CategoryController controller =
            new CategoryController(categoryRepository, subCategoryRepository, categoryService);

    @Test
    @DisplayName("하위 카테고리가 하나도 없어도 200 과 빈 배열 — 400 이면 관리자 화면이 열리지 않는다")
    void allSubcategoriesReturnsEmptyListNotBadRequest() {
        when(subCategoryRepository.findAll()).thenReturn(List.of());

        ResponseEntity<?> response = controller.loadAllSubcategories();

        assertThat(response.getStatusCode())
                .as("0건을 400 으로 돌려주면 /admin/category 가 토스트만 띄우고 끝난다 — "
                        + "그래서 첫 하위 카테고리를 영영 만들 수 없다")
                .isEqualTo(HttpStatus.OK);
        assertThat((List<?>) response.getBody())
                .as("본문은 빈 배열이어야 한다. 오류 문자열이면 화면이 목록으로 다루지 못한다")
                .isEmpty();
    }

    @Test
    @DisplayName("특정 카테고리에 하위가 없어도 200 과 빈 배열 — 상품 등록 폼이 열리다 말면 안 된다")
    void subcategoriesOfACategoryReturnsEmptyListNotBadRequest() {
        when(categoryRepository.findSubCategoriesByCategoryId(7L))
                .thenReturn(List.<Subcategory>of());

        ResponseEntity<?> response = controller.getSubcategories("7");

        assertThat(response.getStatusCode())
                .as("하위 카테고리가 아직 없는 카테고리는 정상이다. 400 이면 admin/Index.jsx 의 "
                        + "getSubs 가 처리되지 않은 거절로 끝난다(그쪽에 catch 가 없다)")
                .isEqualTo(HttpStatus.OK);
        assertThat((List<?>) response.getBody()).isEmpty();
    }

    @Test
    @DisplayName("카테고리 id 가 숫자가 아니면 그건 진짜 잘못된 요청이라 400 이다")
    void nonNumericCategoryIdIsStillABadRequest() {
        // 0건과 잘못된 입력을 구분하는 것이 이 변경의 핵심이다. 후자는 400 이 맞다.
        ResponseEntity<?> response = controller.getSubcategories("어쩌구");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
