package com.project.catalog_service.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.catalog_service.dto.HomeDtos.HomeSectionAdminView;
import com.project.catalog_service.dto.HomeDtos.HomeSectionRequest;
import com.project.catalog_service.dto.HomeDtos.HomeSectionView;
import com.project.catalog_service.dto.HomeDtos.HomeView;
import com.project.catalog_service.model.HomeSection;
import com.project.catalog_service.repository.jpa.data.CategoryRepository;
import com.project.catalog_service.repository.jpa.data.HomeSectionRepository;
import com.project.common.dto.ProductDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 홈 화면 구성.
 *
 * <p><b>예전에는 이 자리가 캐시 워밍 함수였다.</b> {@code /api/product/frontpage} 가
 * {@code warmUpProductCaches(4)} 를 부르고 그 부산물을 홈 데이터로 썼다. 이름이 말하듯
 * 그 함수는 캐시를 데우는 용도이고, 홈이 필요로 하는 것(무엇을 어떤 순서로 보여줄지)은
 * 아무것도 모른다. 그래서 카테고리 × 서브카테고리를 전부 돌고, 결과를 한 배열로 평탄화해
 * 내려보냈다 — 프론트가 다시 나눠야 했고 응답은 카테고리 수에 비례해 커졌다.
 *
 * <p>여기서는 <b>섹션이 정의를 갖고</b>, 섹션마다 필요한 만큼만 조회해 그룹핑한 채로
 * 내려보낸다. 조회 자체는 기존 {@code ProductService} 의 캐시된 메서드를 그대로 쓴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HomeSectionService {

    /** {@code getProductsByCategory} 는 커서가 음수면 "처음부터"를 뜻한다(null 이면 NPE). */
    private static final long FROM_START = -1L;

    private final HomeSectionRepository sectionRepository;
    private final CategoryRepository categoryRepository;
    private final ProductService productService;

    // ── 공개 ────────────────────────────────────────────────────────────

    /**
     * 첫 화면 전체.
     *
     * <p>섹션 하나가 실패해도 <b>홈 전체를 죽이지 않는다.</b> 첫 화면은 사이트의 얼굴이라,
     * 카테고리 하나가 지워졌다고 빈 페이지가 되면 안 된다. 실패한 섹션은 빠지고 로그만 남는다.
     *
     * <p>상품이 하나도 없는 섹션도 뺀다 — 제목만 있고 아래가 빈 줄은 고장처럼 보인다.
     */
    public HomeView home() {
        LocalDateTime now = LocalDateTime.now();
        List<HomeSectionView> views = new ArrayList<>();

        for (HomeSection section : sectionRepository.findVisible(now)) {
            List<ProductDto> products;
            try {
                products = productsOf(section);
            } catch (RuntimeException e) {
                log.warn("홈 섹션을 건너뛴다 — 상품 조회 실패: sectionId={}, 원인={}",
                        section.getSectionId(), e.getMessage());
                continue;
            }

            if (products.isEmpty()) {
                continue;
            }
            Link link = linkOf(section);
            views.add(new HomeSectionView(section.getSectionId(), section.getTitle(),
                    section.getLayout(), link.categoryId(), link.subcategoryId(), products));
        }

        return new HomeView(views);
    }

    /** "더 보기"가 갈 곳 — 탐색 화면이 쓰는 id. 문자열(TSID)이고, 모르면 null. */
    private record Link(String categoryId, String subcategoryId) {
        static final Link NONE = new Link(null, null);
    }

    /**
     * 섹션의 카테고리·하위 카테고리 <b>이름</b>을 탐색 화면의 <b>id</b> 로 바꾼다.
     *
     * <p>링크를 못 만드는 것은 상품을 못 그리는 것보다 가벼운 실패다 — 여기서 무엇이
     * 잘못돼도 섹션은 그대로 내보내고 링크만 비운다(프론트는 그때 전체 목록으로 보낸다).
     * 하위 카테고리를 찾지 못하면 카테고리까지만 건다.
     */
    private Link linkOf(HomeSection section) {
        try {
            Long categoryId = categoryRepository.findIdByCategoryName(section.getCategoryName());
            if (categoryId == null) {
                return Link.NONE;
            }
            String subcategoryId = null;
            if (section.isSubcategorySection() && section.getSubcategoryName() != null) {
                subcategoryId = categoryRepository.findSubCategoriesByCategoryId(categoryId).stream()
                        .filter(sc -> section.getSubcategoryName().equals(sc.getSubcategoryName()))
                        .map(sc -> String.valueOf(sc.getSubcategoryId()))
                        .findFirst()
                        .orElse(null);
            }
            return new Link(String.valueOf(categoryId), subcategoryId);
        } catch (RuntimeException e) {
            log.warn("홈 섹션 링크를 비운다 — 카테고리 조회 실패: sectionId={}, 원인={}",
                    section.getSectionId(), e.getMessage());
            return Link.NONE;
        }
    }

    private List<ProductDto> productsOf(HomeSection section) {
        if (section.isSubcategorySection()) {
            // 페이지 0 부터. 캐시 키에 pageSize 가 들어가므로 limit 이 다른 섹션끼리
            // 서로의 결과를 덮어쓰지 않는다(ProductService 의 해당 주석 참조).
            return productService.getProductsByCategoryAndSubcategory(
                    section.getCategoryName(), section.getSubcategoryName(), 0, section.getItemLimit());
        }
        return productService.getProductsByCategory(
                section.getCategoryName(), FROM_START, section.getItemLimit());
    }

    // ── 관리자 ──────────────────────────────────────────────────────────

    public List<HomeSectionAdminView> all() {
        LocalDateTime now = LocalDateTime.now();
        return sectionRepository.findAllByOrderBySortOrderAscSectionIdAsc().stream()
                .map(s -> HomeSectionAdminView.of(s, now, categoryExists(s.getCategoryName())))
                .toList();
    }

    @Transactional
    public HomeSectionAdminView create(HomeSectionRequest request) {
        requireCategory(request.categoryName());

        HomeSection section = HomeSection.builder()
                .title(request.title())
                .kind(request.kind())
                .categoryName(request.categoryName())
                .subcategoryName(blankToNull(request.subcategoryName()))
                .layout(request.layout())
                .itemLimit(request.itemLimit() == null ? 10 : request.itemLimit())
                .sortOrder(request.sortOrder() == null ? 0 : request.sortOrder())
                // 기본은 비공개다. 만들자마자 첫 화면에 뜨면 작업 중인 섹션이 노출된다.
                .published(Boolean.TRUE.equals(request.published()))
                .startsAt(request.startsAt())
                .endsAt(request.endsAt())
                .build();

        HomeSection saved = sectionRepository.save(section);
        log.info("홈 섹션 생성: id={}, published={}", saved.getSectionId(), saved.isPublished());
        return HomeSectionAdminView.of(saved, LocalDateTime.now(), true);
    }

    @Transactional
    public HomeSectionAdminView update(Long id, HomeSectionRequest request) {
        HomeSection section = sectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("홈 섹션을 찾을 수 없습니다."));

        requireCategory(request.categoryName());

        section.setTitle(request.title());
        section.setKind(request.kind());
        section.setCategoryName(request.categoryName());
        section.setSubcategoryName(blankToNull(request.subcategoryName()));
        section.setLayout(request.layout());
        if (request.itemLimit() != null) {
            section.setItemLimit(request.itemLimit());
        }
        if (request.sortOrder() != null) {
            section.setSortOrder(request.sortOrder());
        }
        if (request.published() != null) {
            section.setPublished(request.published());
        }
        // 기간은 null 이 "제한 없음"을 뜻하므로 그대로 덮어쓴다.
        section.setStartsAt(request.startsAt());
        section.setEndsAt(request.endsAt());

        log.info("홈 섹션 수정: id={}, published={}", id, section.isPublished());
        return HomeSectionAdminView.of(section, LocalDateTime.now(), true);
    }

    @Transactional
    public void delete(Long id) {
        if (!sectionRepository.existsById(id)) {
            throw new IllegalArgumentException("홈 섹션을 찾을 수 없습니다.");
        }
        sectionRepository.deleteById(id);
        log.warn("홈 섹션 삭제: id={}", id);
    }

    /**
     * 저장 시점에 카테고리 존재를 확인한다.
     *
     * <p>오타를 여기서 잡지 않으면 증상이 "홈에 그 섹션만 안 나온다"로 나타난다 —
     * 어디가 틀렸는지 화면에서는 알 수 없다. 반대로 <b>저장 뒤에</b> 카테고리가 사라지는
     * 경우는 막지 않는다(그때는 섹션이 조용히 빠지고 어드민 목록이 알려 준다).
     */
    private void requireCategory(String categoryName) {
        if (!categoryExists(categoryName)) {
            throw new IllegalArgumentException("카테고리를 찾을 수 없습니다: " + categoryName);
        }
    }

    private boolean categoryExists(String categoryName) {
        return categoryName != null
                && categoryRepository.findByCategoryName(categoryName).isPresent();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
