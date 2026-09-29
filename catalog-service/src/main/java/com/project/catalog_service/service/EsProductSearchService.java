package com.project.catalog_service.service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Service;

import com.project.catalog_service.document.ProductDocument;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 상품 텍스트 검색의 Elasticsearch 경로 (docs/07 R-C1).
 *
 * search.engine=es 일 때만 빈이 생성된다(기본은 postgres — 기존 LIKE 경로 유지).
 * 패턴: "ES 로 관련도 검색 → productId 목록 → Postgres 에서 상세/파셋 하이드레이션".
 * SearchService 가 이 목록을 IN 조건으로 사용하므로 응답 형태(SearchResultDto)는 변하지 않고,
 * 선행 와일드카드 LIKE 풀스캔(docs/06 CAT-1)만 관련도 기반 전문 검색으로 대체된다.
 *
 * 대상 인덱스는 배치가 롤오버하는 alias(product-alias)이며, 필드는 ProductDocument 의
 * 한/영/일 멀티필드(nori·standard·kuromoji — 배치의 명시적 매핑 생성으로 실제 적용됨)를 사용한다.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "search.engine", havingValue = "es")
@RequiredArgsConstructor
public class EsProductSearchService {

    private final ElasticsearchOperations elasticsearchOperations;

    @Value("${elasticsearch.alias.name.product:product-alias}")
    private String productAliasName;

    /**
     * 검색어에 매칭되는 productId 목록을 관련도 순으로 반환한다.
     * 실패 시 예외를 그대로 던진다 — 폴백(기존 LIKE 경로) 판단은 호출자(SearchService)가 한다.
     */
    public List<Long> searchProductIds(String term, int limit) {
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.multiMatch(m -> m
                        .query(term)
                        .fields("name", "name.english", "name.japanese",
                                "brand", "brand.english", "brand.japanese",
                                "description", "description.english", "description.japanese")))
                .withPageable(PageRequest.of(0, limit))
                .build();

        SearchHits<ProductDocument> hits = elasticsearchOperations.search(
                query, ProductDocument.class, IndexCoordinates.of(productAliasName));

        return hits.getSearchHits().stream()
                .map(hit -> hit.getContent().getProductId())
                .filter(Objects::nonNull)
                .map(Long::valueOf)
                .collect(Collectors.toList());
    }
}
