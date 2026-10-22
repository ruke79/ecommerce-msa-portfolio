package com.project.catalog_service.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.catalog_service.repository.jpa.data.ProductDetailsRepository;
import com.project.catalog_service.repository.jpa.data.ProductRepository;
import com.project.catalog_service.repository.jpa.data.ProductSkuRepository;
import com.project.common.dto.ProductDetailsDto;

import lombok.RequiredArgsConstructor;

/**
 * 검색 첫 쪽의 필터 메뉴(브랜드 · 색상 · 사이즈 · 상세) — 카테고리마다 중복 없는 값, 캐시(기본 10분).
 *
 * <p>🐛 2026-10-19 데이터 규모 측정(상품 100만, docs/83 §5)에서 실패 53.61% 였다. 예전 {@code SearchService} 는
 * <ul>
 *   <li>카테고리의 상품 id 를 <b>전부</b> 읽어 {@code IN (:ids)} 로 색상 · 사이즈 · 상세를 다시 물었다 — 바인드 변수가
 *       65,535 를 넘는 카테고리(fashion ~12만 · books ~13만 …)는 {@code PSQLException} 으로 그 검색이 매번 실패했다
 *       (catalog 로그 96건). 실패는 바깥 {@code search_cache} 에 남지 않아 요청마다 처음부터 다시 돌았다.</li>
 *   <li>브랜드는 DISTINCT 없이 상품마다 한 줄(~12만 문자열)이었다 — GC 가 초당 최대 690ms.</li>
 *   <li>이 메서드들의 {@code @Cacheable} 은 같은 클래스 안에서 직접 불러 프록시를 거치지 않았다 — 한 번도 캐시되지 않았다.</li>
 * </ul>
 * 이제 값은 DB 안에서 조인 + DISTINCT 로 만들고(앱으로 id 를 가져오지 않는다), 따로 된 빈이라 캐시가 실제로 걸린다.
 * {@code sync = true} — 만료 순간 같은 카테고리를 여러 스레드가 함께 다시 만들지 않는다(파드 안에서). 🐛 2026-10-19 전에는 이름뿐이었다 —
 * Spring Data Redis 3.x 는 잠금을 캐시 작성기에 맡기고 이 서비스의 작성기는 잠그지 않는다. 이제 {@code SingleFlightCache} 가 키마다 잠근다.
 * 비우기는 상품 쓰기의 {@code @CacheEvict}(ProductService) 가 한다.
 *
 * <p>캐시 값은 {@link ArrayList} 다 — 캐시 직렬화기는 타입 이름을 남기므로 {@code List.of()} 의 내부 클래스는 읽을 수 없다.
 *
 * <p>[JA] 検索 1 ページ目のフィルターメニュー。以前は商品 id を全部読んで IN に展開し、65,535 を超えるカテゴリで毎回失敗していた。
 * ブランドは DISTINCT なしで、同じクラス内の直接呼び出しで @Cacheable も効いていなかった。DB 内の結合 + DISTINCT と別 Bean のキャッシュに。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SearchFilterFacets {

    private final ProductRepository productRepository;
    private final ProductSkuRepository productSkuRepository;
    private final ProductDetailsRepository productDetailsRepository;

    @Cacheable(value = "brands_cache", key = "'id:' + #categoryId", cacheManager = "redisCacheManager", sync = true)
    public List<String> brands(Long categoryId) {
        return new ArrayList<>(productRepository.findDistinctBrandsByCategoryId(categoryId));
    }

    /** 카테고리를 이름으로 받은(또는 없는 — null 이면 전체) 검색의 브랜드. */
    @Cacheable(value = "brands_cache", key = "'name:' + #categoryName", cacheManager = "redisCacheManager", sync = true)
    public List<String> brandsByCategoryName(String categoryName) {
        return new ArrayList<>(productRepository.findDistinctBrandsByCategoryName(categoryName));
    }

    @Cacheable(value = "colors_cache", key = "#categoryId == null ? 'all' : #categoryId", cacheManager = "redisCacheManager", sync = true)
    public List<String> colors(Long categoryId) {
        return new ArrayList<>(categoryId == null ? productSkuRepository.findAllColors()
                : productSkuRepository.findColorsByCategoryId(categoryId));
    }

    @Cacheable(value = "sizes_cache", key = "#categoryId == null ? 'all' : #categoryId", cacheManager = "redisCacheManager", sync = true)
    public List<String> sizes(Long categoryId) {
        return new ArrayList<>(categoryId == null ? productSkuRepository.findAllSizes()
                : productSkuRepository.findSizesByCategoryId(categoryId));
    }

    @Cacheable(value = "details_cache", key = "#categoryId == null ? 'all' : #categoryId", cacheManager = "redisCacheManager", sync = true)
    public List<ProductDetailsDto> details(Long categoryId) {
        List<Object[]> rows = categoryId == null ? productDetailsRepository.findDistinctNameValues()
                : productDetailsRepository.findDistinctNameValuesByCategoryId(categoryId);
        List<ProductDetailsDto> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            out.add(ProductDetailsDto.builder().name((String) row[0]).value((String) row[1]).build());
        }
        return out;
    }
}
