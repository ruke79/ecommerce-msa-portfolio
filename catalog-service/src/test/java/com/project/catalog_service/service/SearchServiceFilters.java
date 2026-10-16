package com.project.catalog_service.service;

/** 다른 패키지의 테스트(컨테이너)가 {@link SearchService} 의 필터 변환을 쓰기 위한 다리. */
public final class SearchServiceFilters {

    private SearchServiceFilters() {
    }

    public static String prefixRegex(String raw) {
        return SearchService.prefixRegex(SearchService.filterValues(raw));
    }
}
