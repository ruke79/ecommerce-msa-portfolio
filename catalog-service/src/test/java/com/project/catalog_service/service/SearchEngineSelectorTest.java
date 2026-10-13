package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.project.catalog_service.repository.jpa.data.CategoryRepository;
import com.project.catalog_service.repository.jpa.data.ProductDetailsRepository;
import com.project.catalog_service.repository.jpa.data.ProductRepository;
import com.project.catalog_service.repository.jpa.data.ProductSkuRepository;
import com.project.catalog_service.repository.jpa.data.SubcategoryRepository;
import com.project.common.dto.catalog.request.SearchParamsRequest;
import com.project.common.featureflag.FileFlagProvider;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;

/**
 * 검색 엔진 선택 — 기능 플래그 catalog.search-engine(docs/74).
 * [JA] 検索エンジンの選択 — 機能フラグ catalog.search-engine(docs/74)。
 */
class SearchEngineSelectorTest {

    @TempDir Path dir;
    private OpenFeatureAPI api;

    @AfterEach
    void tearDown() {
        if (api != null) api.shutdown();
    }

    static class Params extends SearchParamsRequest {
        Params(String search) { super(); setSearch(search); setPage(1); setPageSize(10); }
    }

    private Client flags(int esWeight) throws Exception {
        Path f = dir.resolve("flags.json");
        Files.writeString(f, """
            { "flags": { "catalog.search-engine": {
                "state": "ENABLED",
                "variants": { "es": "es", "postgres": "postgres" },
                "defaultVariant": "postgres",
                "targeting": { "fractional": [ ["es", %d], ["postgres", %d] ] },
                "metadata": { "owner": "catalog", "expires": "2027-03-31" } } } }
            """.formatted(esWeight, 100 - esWeight), StandardCharsets.UTF_8);
        api = OpenFeatureAPI.createIsolated();
        api.setProviderAndWait(new FileFlagProvider(f, Duration.ofSeconds(5)));
        return api.getClient("test");
    }

    @Test
    @DisplayName("비율대로 나뉘고, 같은 검색어는 대소문자 · 공백이 달라도 같은 엔진이다")
    void splitsByNormalizedTerm() throws Exception {
        SearchEngineSelector s = new SearchEngineSelector(flags(30), true);

        long es = IntStream.range(0, 5_000).filter(i -> s.select(new Params("term " + i)) == SearchEngine.ES).count();
        assertThat(es).isBetween(1300L, 1700L);
        SearchEngine nike = s.select(new Params("Nike  Air"));
        assertThat(s.select(new Params(" nike air "))).isEqualTo(nike);
    }

    @Test
    @DisplayName("0% 면 ES 가 있어도 전부 POSTGRES, 100% 면 전부 ES")
    void zeroAndHundred() throws Exception {
        SearchEngineSelector zero = new SearchEngineSelector(flags(0), true);
        assertThat(IntStream.range(0, 500).mapToObj(i -> zero.select(new Params("t" + i)))).containsOnly(SearchEngine.POSTGRES);
        api.shutdown();
        SearchEngineSelector all = new SearchEngineSelector(flags(100), true);
        assertThat(IntStream.range(0, 500).mapToObj(i -> all.select(new Params("t" + i)))).containsOnly(SearchEngine.ES);
    }

    @Test
    @DisplayName("🔴 ES 가 없는 배포(OCI)에서는 플래그를 평가하지 않고 POSTGRES — 추적에 거짓 'es' 를 남기지 않는다")
    void esNotDeployedNeverEvaluates() {
        Client client = mock(Client.class);
        SearchEngineSelector s = new SearchEngineSelector(client, false);

        assertThat(s.select(new Params("nike"))).isEqualTo(SearchEngine.POSTGRES);
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("검색어가 없으면 평가하지 않는다 — 엔진이 결과에 영향이 없다")
    void noSearchTermNeverEvaluates() {
        Client client = mock(Client.class);
        SearchEngineSelector s = new SearchEngineSelector(client, true);

        assertThat(s.select(new Params(null))).isEqualTo(SearchEngine.POSTGRES);
        assertThat(s.select(new Params("   "))).isEqualTo(SearchEngine.POSTGRES);
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("플래그 파일이 없으면 플래그 이전의 동작 — ES 가 있으면 ES")
    void missingFlagKeepsThePreviousBehaviour() {
        api = OpenFeatureAPI.createIsolated();
        api.setProviderAndWait(new FileFlagProvider(dir.resolve("none.json"), Duration.ofSeconds(5)));
        SearchEngineSelector s = new SearchEngineSelector(api.getClient("test"), true);

        assertThat(s.select(new Params("nike"))).isEqualTo(SearchEngine.ES);
    }

    // ---------------------------------------------------------------- 캐시 키

    @Configuration
    @EnableCaching
    static class CacheConfig {
        static JPAQueryFactory queryFactory;

        @Bean ConcurrentMapCacheManager redisCacheManager() { return new ConcurrentMapCacheManager(); }

        @SuppressWarnings({ "rawtypes", "unchecked" })
        @Bean SearchService searchService() {
            JPAQueryFactory qf = mock(JPAQueryFactory.class);
            JPAQuery count = mock(JPAQuery.class, Answers.RETURNS_SELF);
            org.mockito.Mockito.when(count.fetchOne()).thenReturn(0L);
            JPAQuery list = mock(JPAQuery.class, Answers.RETURNS_SELF);
            org.mockito.Mockito.when(list.fetch()).thenReturn(Collections.emptyList());
            org.mockito.Mockito.when(qf.select(org.mockito.ArgumentMatchers.any(com.querydsl.core.types.Expression.class))).thenReturn(count);
            org.mockito.Mockito.when(qf.selectFrom(org.mockito.ArgumentMatchers.any())).thenReturn(list);
            queryFactory = qf;
            return new SearchService(mock(CategoryRepository.class), mock(SubcategoryRepository.class),
                    mock(ProductRepository.class), mock(ProductSkuRepository.class), mock(ProductDetailsRepository.class),
                    qf, mock(EventPricing.class));
        }
    }

    /**
     * 🔴 같은 검색이라도 엔진이 다르면 <b>다른 캐시 항목</b>이다. 키에 엔진이 없으면 비율을 바꾼 뒤 최대 60분 동안
     * 반대쪽 엔진의 결과가 나가고, 추적의 변형 태그와 실제 결과가 어긋난다.
     */
    @Test
    @DisplayName("🔴 검색 캐시 키에 엔진이 들어간다 — 엔진이 바뀌면 캐시가 맞지 않는다")
    void cacheKeyIncludesTheEngine() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(CacheConfig.class)) {
            SearchService svc = ctx.getBean(SearchService.class);
            Params p = new Params("nike");

            svc.searchProducts(p, SearchEngine.POSTGRES);
            svc.searchProducts(p, SearchEngine.POSTGRES);
            svc.searchProducts(p, SearchEngine.ES);

            // 처음 한 번 + 엔진이 바뀐 한 번 = 2. 같은 엔진의 두 번째는 캐시에서 나온다.
            org.mockito.Mockito.verify(CacheConfig.queryFactory, org.mockito.Mockito.times(2))
                    .selectFrom(org.mockito.ArgumentMatchers.any());
        }
    }
}
