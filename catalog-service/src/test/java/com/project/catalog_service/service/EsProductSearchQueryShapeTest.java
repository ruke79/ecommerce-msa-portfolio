package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.elasticsearch.core.query.SourceFilter;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.catalog_service.document.ProductDocument;

/**
 * 이름 검색이 ES 에 묻는 모양 — productId 만 받고 전체 건수를 세지 않는다.
 *
 * <p>🐛 2026-11-23 실습(상품 1,000만): 후보 1,000건을 문서 전체(SKU · 사이즈 · 하위 분류 중첩까지, 750KB)로 받아
 * ProductDocument 로 바꿨다 — ES 는 33ms 인데 캐시에 없는 이름 검색이 부하 없이 0.5초였다(docs/91 §4-1).
 * [JA] 名前検索が ES に問う形 — productId だけを受け取り、総件数を数えない。
 */
@DisplayName("ES 이름 검색 — 질의 모양")
class EsProductSearchQueryShapeTest {

    @Test
    @DisplayName("🔴 _source 는 productId 만 · track_total_hits 끔 · 후보 수는 limit — 문서 전체를 받으면 1,000건 변환에 0.5초")
    @SuppressWarnings("unchecked")
    void fetchesOnlyProductIds() {
        ElasticsearchOperations ops = mock(ElasticsearchOperations.class);
        SearchHits<ProductDocument> hits = mock(SearchHits.class);
        SearchHit<ProductDocument> hit = mock(SearchHit.class);
        ProductDocument doc = ProductDocument.builder().productId("42").build();
        when(hit.getContent()).thenReturn(doc);
        when(hits.getSearchHits()).thenReturn(List.of(hit));
        when(ops.search(any(Query.class), eq(ProductDocument.class), any(IndexCoordinates.class))).thenReturn(hits);

        EsProductSearchService service = new EsProductSearchService(ops, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        ReflectionTestUtils.setField(service, "productAliasName", "product-alias");

        assertThat(service.searchProductIds("Sofa", 1000)).containsExactly(42L);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<IndexCoordinates> index = ArgumentCaptor.forClass(IndexCoordinates.class);
        verify(ops).search(query.capture(), eq(ProductDocument.class), index.capture());

        assertThat(index.getValue().getIndexName()).isEqualTo("product-alias");
        SourceFilter source = query.getValue().getSourceFilter();
        assertThat(source).as("문서 전체를 받지 않는다").isNotNull();
        assertThat(source.getIncludes()).containsExactly("productId");
        assertThat(source.fetchSource()).isNotEqualTo(Boolean.FALSE);
        assertThat(query.getValue().getTrackTotalHits()).as("전체 건수는 쓰지 않는다").isFalse();
        assertThat(query.getValue().getPageable().getPageSize()).isEqualTo(1000);
    }
}
