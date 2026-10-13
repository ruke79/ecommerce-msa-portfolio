package com.project.catalog_service.service;

/**
 * 텍스트 검색을 무엇으로 하는가. 검색 결과 캐시 키의 일부다 — 같은 검색어라도 엔진이 다르면 결과가 다르다(docs/74).
 *
 * <p>[JA] テキスト検索を何で行うか。検索結果キャッシュのキーの一部である — 同じ検索語でもエンジンが違えば結果が違う(docs/74)。
 */
public enum SearchEngine {
    /** 선행 와일드카드 LIKE(docs/06 CAT-1). ES 가 없는 배포의 유일한 경로. */
    POSTGRES,
    /** ES 관련도 검색 → id IN (docs/07 R-C1). ES 장애 시에는 SearchService 가 POSTGRES 경로로 폴백한다. */
    ES
}
