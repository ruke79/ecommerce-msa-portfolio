package com.project.gatewayserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * B-84: 유입 제한은 라우트마다 정확히 하나, JWT 필터 뒤 — 그리고 Redis 가 끊겨도 게이트웨이가 멈추지 않는다.
 *
 * <p>🐛 예전: default-filters 에 하나 + 로그인 라우트에 하나. RedisRateLimiter 는 설정을 라우트 id 로 저장하고 Redis 키도 라우트 id 라,
 * 한 라우트의 두 필터가 같은 버킷에서 요청마다 토큰 두 개를 썼다 — 같은 라우트의 리뷰 보기 · 토큰 갱신이 버킷을 비워 갱신이 429 →
 * 화면이 로그아웃시켰다. Redis 명령 타임아웃도 없어(기본 60초) Redis 가 잠깐 끊기면 모든 요청이 멈췄다.
 *
 * <p>[JA] 流入制限はルートごとにちょうど一つ、JWT フィルターの後 — Redis が切れてもゲートウェイは止まらない。
 */
@DisplayName("B-84: 게이트웨이 유입 제한 — 라우트마다 하나 · JWT 뒤 · Redis 타임아웃")
class RateLimiterPlacementTest {

    private static StandardEnvironment env() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application-local", new ClassPathResource("application-local.yml"))
                .forEach(env.getPropertySources()::addLast);
        return env;
    }

    private static GatewayProperties gateway() throws Exception {
        return new Binder(org.springframework.boot.context.properties.source.ConfigurationPropertySources.get(env()))
                .bind("spring.cloud.gateway.server.webflux", Bindable.of(GatewayProperties.class)).get();
    }

    private static long limiters(List<FilterDefinition> filters) {
        return filters.stream().filter(f -> "RequestRateLimiter".equals(f.getName())).count();
    }

    private static RouteDefinition route(GatewayProperties g, String id) {
        return g.getRoutes().stream().filter(r -> r.getId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("🔴 default-filters 에 유입 제한이 없고, 모든 라우트에 정확히 하나 — 둘이면 같은 버킷에서 토큰을 두 번 쓴다")
    void exactlyOneLimiterPerRoute() throws Exception {
        GatewayProperties g = gateway();
        assertThat(limiters(g.getDefaultFilters())).isZero();
        assertThat(g.getRoutes()).allSatisfy(r -> assertThat(limiters(r.getFilters())).as(r.getId()).isEqualTo(1));
    }

    @Test
    @DisplayName("🔴 JWT 라우트의 유입 제한은 JwtAuthorization 뒤 — 앞이면 User-Id 가 없어 로그인 사용자도 IP 버킷에 묶인다")
    void limiterRunsAfterJwt() throws Exception {
        for (RouteDefinition r : gateway().getRoutes()) {
            List<String> names = r.getFilters().stream().map(FilterDefinition::getName).toList();
            int jwt = names.indexOf("JwtAuthorization");
            if (jwt >= 0) {
                assertThat(names.indexOf("RequestRateLimiter")).as(r.getId()).isGreaterThan(jwt);
            }
        }
    }

    @Test
    @DisplayName("🔴 로그인 라우트만 강한 제한(1/s) — 리뷰 보기 · 토큰 갱신은 그 버킷을 쓰지 않는다")
    void loginBucketIsSeparateFromReadsAndRefresh() throws Exception {
        GatewayProperties g = gateway();
        RouteDefinition login = route(g, "user-service-public");
        RouteDefinition read = route(g, "user-service-public-read");

        assertThat(limiterArg(login, "redis-rate-limiter.replenishRate")).isEqualTo("1");
        assertThat(limiterArg(read, "redis-rate-limiter.replenishRate")).isEqualTo("20");
        assertThat(read.getPredicates().get(0).getArgs().values())
                .contains("/user-service/api/token/refresh", "/user-service/api/review/**");
        assertThat(login.getPredicates().get(0).getArgs().values())
                .doesNotContain("/user-service/api/token/refresh", "/user-service/api/review/**")
                .contains("/user-service/api/auth/public/**");
        // 읽기 라우트가 로그인 라우트보다 앞이어도 뒤여도 겹치지 않는다 — 경로가 서로 다르다
        assertThat(IntStream.range(0, g.getRoutes().size()).filter(i -> g.getRoutes().get(i).getId().startsWith("user-service-public")).count())
                .isEqualTo(2);
    }

    private static String limiterArg(RouteDefinition r, String key) {
        return r.getFilters().stream().filter(f -> "RequestRateLimiter".equals(f.getName())).findFirst().orElseThrow()
                .getArgs().get(key);
    }

    @Test
    @DisplayName("🔴 Redis 명령 타임아웃 ≤ 1초 — 기본 60초면 Redis 가 잠깐 끊겨도 모든 요청이 멈춘다(짧으면 fail-open)")
    void redisCommandsTimeOutQuickly() throws Exception {
        Duration timeout = Binder.get(env()).bind("spring.data.redis.timeout", Duration.class)
                .orElseThrow(() -> new AssertionError("spring.data.redis.timeout 이 없다 — Lettuce 기본 60초"));
        assertThat(timeout).isLessThanOrEqualTo(Duration.ofSeconds(1));
        assertThat(Binder.get(env()).bind("spring.data.redis.connect-timeout", Duration.class).get())
                .isLessThanOrEqualTo(Duration.ofSeconds(2));
    }
}
