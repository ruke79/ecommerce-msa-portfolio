package com.project.catalog_service.service;

/** 다른 패키지의 테스트(컨테이너)가 {@link SearchService} 의 필터 변환을 쓰기 위한 다리. */
public final class SearchServiceFilters {

    private SearchServiceFilters() {
    }

    /** 사이즈 · 색상 값마다의 앞부분 일치 LIKE 패턴(이스케이프 {@code !}). */
    public static java.util.List<String> prefixLikes(String raw) {
        return SearchService.prefixLikes(SearchService.filterValues(raw));
    }
}
