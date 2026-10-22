package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import com.project.catalog_service.dto.HomeDtos.HomeSectionView;
import com.project.catalog_service.dto.HomeDtos.HomeView;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 첫 화면 구성 캐시 — 2026-10-18 부하 측정에서 catalog 시간의 65% 가 홈이었다(요청당 27ms, docs/83 §5).
 *
 * <p>[JA] トップ画面の構成のキャッシュ — TTL の間は作り直さない · 期限切れは一つのスレッドだけ · 失敗は入れない · 0 で無効。
 */
@DisplayName("HomeViewCache — 첫 화면 구성 캐시")
class HomeViewCacheTest {

    /** 손으로 넘기는 시계. */
    static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-10-18T06:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    final HomeSectionService sections = mock(HomeSectionService.class);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final MovableClock clock = new MovableClock();

    private static HomeView view(String title) {
        return new HomeView(List.of(new HomeSectionView(1L, title, "grid", null, null, List.of())));
    }

    private double count(String result) {
        return registry.get("catalog.home.cache").tag("result", result).counter().count();
    }

    @Test
    @DisplayName("🔴 TTL 동안은 다시 만들지 않는다 — 섹션 · 카테고리 · 상품 목록을 요청마다 읽던 것(지표 hit · miss)")
    void reusesWithinTtlAndRebuildsAfter() {
        when(sections.home()).thenReturn(view("v1"), view("v2"));
        HomeViewCache cache = new HomeViewCache(sections, Duration.ofSeconds(30), registry, clock);

        assertThat(cache.get().sections().get(0).title()).isEqualTo("v1");
        clock.advance(Duration.ofSeconds(29));
        assertThat(cache.get().sections().get(0).title()).isEqualTo("v1");
        verify(sections, times(1)).home();

        clock.advance(Duration.ofSeconds(1));   // 30초 — 만료
        assertThat(cache.get().sections().get(0).title()).isEqualTo("v2");
        verify(sections, times(2)).home();
        assertThat(count("miss")).isEqualTo(2);
        assertThat(count("hit")).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 만료 순간의 동시 요청 — 한 스레드만 만들고 나머지는 그 값을 받는다")
    void onlyOneThreadRebuilds() throws Exception {
        CountDownLatch building = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger builds = new AtomicInteger();
        when(sections.home()).thenAnswer(inv -> {
            builds.incrementAndGet();
            building.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return view("v1");
        });
        HomeViewCache cache = new HomeViewCache(sections, Duration.ofSeconds(30), registry, clock);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<HomeView>> results = new ArrayList<>();
            results.add(pool.submit(cache::get));
            assertThat(building.await(10, TimeUnit.SECONDS)).isTrue();   // 첫 스레드가 만드는 중
            for (int i = 0; i < 15; i++) {
                results.add(pool.submit(cache::get));
            }
            Thread.sleep(100);   // 나머지가 자물쇠 앞에 줄 서게
            release.countDown();
            for (Future<HomeView> f : results) {
                assertThat(f.get(10, TimeUnit.SECONDS).sections().get(0).title()).isEqualTo("v1");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(builds.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("만들다 실패하면 담지 않는다 — 다음 요청이 다시 만든다")
    void failureIsNotCached() {
        when(sections.home()).thenThrow(new IllegalStateException("db down")).thenReturn(view("v1"));
        HomeViewCache cache = new HomeViewCache(sections, Duration.ofSeconds(30), registry, clock);

        assertThatThrownBy(cache::get).isInstanceOf(IllegalStateException.class);
        assertThat(cache.get().sections().get(0).title()).isEqualTo("v1");
        verify(sections, times(2)).home();
    }

    @Test
    @DisplayName("관리자가 섹션을 바꾸면 비운다 · TTL 0 이면 매번 만든다(지표 off) · 음수는 기동 실패")
    void invalidateAndZeroTtl() {
        when(sections.home()).thenReturn(view("v1"), view("v2"), view("v3"), view("v4"));
        HomeViewCache cache = new HomeViewCache(sections, Duration.ofSeconds(30), registry, clock);
        cache.get();
        cache.invalidate();
        assertThat(cache.get().sections().get(0).title()).isEqualTo("v2");

        HomeViewCache off = new HomeViewCache(sections, Duration.ZERO, registry, clock);
        assertThat(off.get().sections().get(0).title()).isEqualTo("v3");
        assertThat(off.get().sections().get(0).title()).isEqualTo("v4");
        assertThat(count("off")).isEqualTo(2);

        assertThatThrownBy(() -> new HomeViewCache(sections, Duration.ofSeconds(-1), registry, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("기본 TTL 은 30초 — 설정(CONTENT_HOME_CACHE_TTL)으로만 바꾼다")
    void defaultTtlIs30Seconds() throws Exception {
        List<PropertySource<?>> yml = new YamlPropertySourceLoader()
                .load("local", new ClassPathResource("application-local.yml"));
        assertThat(yml.get(0).getProperty("content.home.cache-ttl")).hasToString("${CONTENT_HOME_CACHE_TTL:PT30S}");
    }
}
