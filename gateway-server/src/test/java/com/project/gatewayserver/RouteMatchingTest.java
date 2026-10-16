package com.project.gatewayserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.handler.predicate.MethodRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

/**
 * 게이트웨이 라우트 — 경로 패턴이 깨끗하고, 겹치는 라우트는 order 로 순서가 정해져 있고, 실제 요청이 의도한 라우트로 간다.
 *
 * <p>🐛 예전: {@code user-service-public} 의 Path 를 여러 줄에 {@code , \} 로 이어 써, YAML 이 {@code \} 를 글자 그대로 남겼다 —
 * {@code "\ /user-service/registrationConfirm"} · {@code "\ /user-service/oauth2/**"} 가 아무것도 맞지 않아 가입 확인 메일 링크와 소셜
 * 로그인 시작이 게이트웨이에서 404 였다(2026-09-28 부터). 또 웹훅 · 공개 인증 라우트는 넓은 라우트에 덮이는데 선언 순서에만 기댔다.
 *
 * <p>[JA] ゲートウェイのルート — 経路パターンがきれいで、重なるルートは order で順序が決まり、実際のリクエストが意図したルートに行く。
 */
@DisplayName("게이트웨이 라우트 — 패턴 · 순서 · 실제 매칭")
class RouteMatchingTest {

    private static List<RouteDefinition> routes() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application-local", new ClassPathResource("application-local.yml"))
                .forEach(env.getPropertySources()::addLast);
        return new Binder(ConfigurationPropertySources.get(env))
                .bind("spring.cloud.gateway.server.webflux", Bindable.of(GatewayProperties.class)).get().getRoutes();
    }

    private static List<String> args(RouteDefinition r, String predicate) {
        return r.getPredicates().stream().filter(p -> p.getName().equals(predicate))
                .flatMap(p -> p.getArgs().values().stream()).toList();
    }

    @Test
    @DisplayName("🔴 모든 Path 패턴은 / 로 시작하고 공백 · 백슬래시가 없다 — 깨진 패턴은 조용히 아무것도 맞지 않는다")
    void pathPatternsAreClean() throws Exception {
        for (RouteDefinition r : routes()) {
            assertThat(args(r, "Path")).as(r.getId()).isNotEmpty()
                    .allSatisfy(p -> assertThat(p).as(r.getId()).startsWith("/").doesNotContain("\\").doesNotContainAnyWhitespaces());
        }
    }

    /** a 가 b 보다 좁은가 — b 가 {@code /x/**} 이고 a 가 {@code /x/…} 이면서 같지 않을 때. */
    private static boolean narrower(String a, String b) {
        return b.endsWith("/**") && !a.equals(b) && a.startsWith(b.substring(0, b.length() - 2));
    }

    @Test
    @DisplayName("🔴 넓은 라우트에 덮이는 좁은 라우트는 order 가 더 작다 — 선언 순서에 기대지 않는다")
    void overlappingRoutesAreOrdered() throws Exception {
        List<RouteDefinition> all = routes();
        List<String> offenders = new ArrayList<>();
        for (RouteDefinition a : all) {
            for (RouteDefinition b : all) {
                if (a == b) {
                    continue;
                }
                boolean covered = args(a, "Path").stream().anyMatch(pa -> args(b, "Path").stream().anyMatch(pb -> narrower(pa, pb)))
                        // 같은 경로라도 조건(Method)이 더 붙은 쪽이 먼저여야 한다
                        || (args(a, "Path").equals(args(b, "Path")) && a.getPredicates().size() > b.getPredicates().size());
                if (covered && a.getOrder() >= b.getOrder()) {
                    offenders.add(a.getId() + "(" + a.getOrder() + ") ⊂ " + b.getId() + "(" + b.getOrder() + ")");
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    private static Predicate<ServerWebExchange> matcher(RouteDefinition r) {
        List<Predicate<ServerWebExchange>> parts = new ArrayList<>();
        for (PredicateDefinition p : r.getPredicates()) {
            if (p.getName().equals("Path")) {
                PathRoutePredicateFactory.Config c = new PathRoutePredicateFactory.Config();
                c.setPatterns(new ArrayList<>(p.getArgs().values()));
                parts.add(new PathRoutePredicateFactory().apply(c));
            } else if (p.getName().equals("Method")) {
                MethodRoutePredicateFactory.Config c = new MethodRoutePredicateFactory.Config();
                c.setMethods(p.getArgs().values().stream().map(String::trim).map(HttpMethod::valueOf).toArray(HttpMethod[]::new));
                parts.add(new MethodRoutePredicateFactory().apply(c));
            }
        }
        return ex -> parts.stream().allMatch(part -> part.test(ex));
    }

    /** 게이트웨이와 같은 규칙 — order 로 안정 정렬한 뒤 처음 맞는 라우트. */
    private static String resolve(List<RouteDefinition> all, HttpMethod method, String path) {
        ServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.method(method, path));
        return all.stream().sorted(Comparator.comparingInt(RouteDefinition::getOrder))
                .filter(r -> matcher(r).test(ex)).map(RouteDefinition::getId).findFirst().orElse("(없음 — 404)");
    }

    @Test
    @DisplayName("🔴 가입 확인 링크 · 소셜 로그인 시작 · 공개 인증 · 웹훅이 의도한 라우트로 간다")
    void requestsReachTheIntendedRoute() throws Exception {
        List<RouteDefinition> all = routes();
        Map<String, String> want = Map.of(
                "/user-service/registrationConfirm", "user-service-public",
                "/user-service/oauth2/authorization/google", "user-service-public",
                "/user-service/login/oauth2/code/google", "user-service-public",
                "/user-service/api/auth/public/signin", "user-service-public",
                "/user-service/api/auth/user", "user-service-auth",
                "/order-service/api/order/payment/stripe/webhook", "order-service-stripe-webhook",
                "/order-service/api/order/payment/portone/webhook", "order-service-portone-webhook",
                "/order-service/api/order/list", "order-service");
        want.forEach((path, id) -> assertThat(resolve(all, HttpMethod.GET, path)).as(path).isEqualTo(id));
        assertThat(resolve(all, HttpMethod.POST, "/catalog-service/api/product")).isEqualTo("catalog-service-write");
        assertThat(resolve(all, HttpMethod.GET, "/catalog-service/api/product")).isEqualTo("catalog-service");
    }
}
