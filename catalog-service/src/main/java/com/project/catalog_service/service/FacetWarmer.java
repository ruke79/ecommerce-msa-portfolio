package com.project.catalog_service.service;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.project.catalog_service.repository.jpa.data.CategoryRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 필터 메뉴 미리 채우기 — 기동 몇 분 뒤와 그 뒤 주기마다 모든 카테고리의 메뉴(브랜드 · 색상 · 사이즈 · 상세)를 차례로 채운다.
 *
 * <p>🐛 2026-11-21 운영 읽기 천장(docs/95 §5-1): 메뉴는 첫 방문 때 계산을 맡겼는데, 200만 상품에서 카테고리 하나가 약 100초라 그동안의
 * 첫 쪽 검색은 대기 상한(1.5초)을 다 기다리고 메뉴 없이 나갔다(측정 p50 1.50초). 이제 손님보다 먼저 채운다.
 *
 * <ul>
 *   <li>없거나 오래된({@code catalog.facets.refresh-after}) 것만 계산한다 — 새것은 건너뛴다(그 회차는 거의 공짜).</li>
 *   <li>카테고리마다 끝날 때까지 기다린다 — 계산 스레드 · 대기열(64)을 한꺼번에 채우지 않는다. 한 카테고리의 상한은
 *       {@code catalog.facets.warm.per-category-timeout-seconds}(기본 660 = 질의 상한 600 + 1분).</li>
 *   <li>클러스터에서 계산은 키마다 한 파드(FacetRefreshClaim) — 다른 파드가 맡은 키는 그 파드가 쓴다.</li>
 *   <li>끄기: {@code catalog.facets.warm.enabled=false}(CATALOG_FACETS_WARM_ENABLED).</li>
 * </ul>
 *
 * <p>[JA] フィルターメニューの事前計算 — 起動数分後とその後の周期ごとに全カテゴリのメニューを順に満たす。ないか古いものだけ、カテゴリごとに
 * 終わるまで待つ(実行器を一度に埋めない)。200 万商品でカテゴリ一つ約 100 秒、初回訪問の検索が 1.5 秒待ってメニューなしだった。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "catalog.facets.warm.enabled", havingValue = "true", matchIfMissing = true)
public class FacetWarmer {

    private final SearchFilterFacets facets;
    private final CategoryRepository categories;
    private final long perCategorySeconds;

    public FacetWarmer(SearchFilterFacets facets, CategoryRepository categories,
            @Value("${catalog.facets.warm.per-category-timeout-seconds:660}") long perCategorySeconds) {
        this.facets = facets;
        this.categories = categories;
        this.perCategorySeconds = perCategorySeconds;
    }

    @Scheduled(initialDelayString = "${catalog.facets.warm.initial-delay-ms:300000}",
            fixedDelayString = "${catalog.facets.warm.interval-ms:3600000}")
    public void warmAll() {
        List<Long> ids;
        try {
            ids = categories.findAllCategoryIds();
        } catch (RuntimeException e) {
            log.warn("필터 메뉴 미리 채우기 — 카테고리 목록을 읽지 못했다 ({})", e.getClass().getSimpleName());
            return;
        }
        long t0 = System.nanoTime();
        int late = 0;
        for (Long id : ids) {
            try {
                facets.warm(id).get(perCategorySeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                late++;
                log.warn("필터 메뉴 미리 채우기 — 카테고리 {} 가 {}초 안에 끝나지 않았다(계산은 계속된다)", id, perCategorySeconds);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("필터 메뉴 미리 채우기 — 카테고리 {} 실패 ({})", id, e.getClass().getSimpleName());
            }
        }
        log.info("필터 메뉴 미리 채우기: 카테고리 {} · 늦음 {} · {}초", ids.size(), late,
                TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0));
    }
}
