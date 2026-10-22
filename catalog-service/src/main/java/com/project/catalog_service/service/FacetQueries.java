package com.project.catalog_service.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.catalog_service.repository.jpa.data.ProductDetailsRepository;
import com.project.catalog_service.repository.jpa.data.ProductRepository;
import com.project.catalog_service.repository.jpa.data.ProductSkuRepository;
import com.project.common.dto.ProductDetailsDto;

import lombok.RequiredArgsConstructor;

/**
 * 필터 메뉴(브랜드 · 색상 · 사이즈 · 상세)를 실제로 만드는 질의 — {@link SearchFilterFacets} 가 <b>요청 스레드 밖</b>(전용 실행기)에서만 부른다.
 *
 * <p>값은 DB 안의 조인 + DISTINCT 다. 상품 1,000만(가장 큰 카테고리 240만)에서는 한 번에 1분이 넘는다(2026-10-20 실습: 사이즈
 * DISTINCT 76초) — 그래서 요청이 기다리지 않게 떼어 냈고, 대신 이 질의의 시간 상한은 길다({@code catalog.facets.query-timeout-seconds},
 * 기본 600초). 상한이 없으면 멈춘 질의가 커넥션을 영영 쥔다.
 *
 * <p>[JA] フィルターメニューを実際に作る照会 — SearchFilterFacets がリクエストスレッドの外でだけ呼ぶ。1,000 万商品では 1 分を超えるので
 * リクエストから切り離し、この照会の時間上限は長くしてある(既定 600 秒)。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, timeoutString = "${catalog.facets.query-timeout-seconds:600}")
public class FacetQueries {

    private final ProductRepository productRepository;
    private final ProductSkuRepository productSkuRepository;
    private final ProductDetailsRepository productDetailsRepository;

    public List<String> brands(Long categoryId) {
        return productRepository.findDistinctBrandsByCategoryId(categoryId);
    }

    /** 카테고리를 이름으로 받은(또는 없는 — null 이면 전체) 검색의 브랜드. */
    public List<String> brandsByCategoryName(String categoryName) {
        return productRepository.findDistinctBrandsByCategoryName(categoryName);
    }

    public List<String> colors(Long categoryId) {
        return categoryId == null ? productSkuRepository.findAllColors() : productSkuRepository.findColorsByCategoryId(categoryId);
    }

    public List<String> sizes(Long categoryId) {
        return categoryId == null ? productSkuRepository.findAllSizes() : productSkuRepository.findSizesByCategoryId(categoryId);
    }

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
