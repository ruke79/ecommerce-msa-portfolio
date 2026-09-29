package com.project.gatewayserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * 게이트웨이 설정이 <b>Spring Cloud Gateway 4.3 의 위치</b>(spring.cloud.gateway.server.webflux)에 있고, 거기서 라우트가 읽힌다.
 *
 * <p>4.3(Spring Cloud 2025.0)부터 서버 설정의 접두사가 바뀌었다. 옛 위치(spring.cloud.gateway.*)는 기동 때 옮겨 읽어 주지만
 * (GatewayServerWebfluxPropertiesMigrationListener, 경고) 다음 메이저에서 빠진다 — 그날 라우트가 <b>조용히 0개</b>가 된다.
 * 기동 테스트(GatewayServerApplicationTests)는 Docker 가 있는 통합 단계에서만 돌아서, 여기서 Docker 없이 바인딩으로 확인한다.
 */
@DisplayName("게이트웨이 설정 위치 — spring.cloud.gateway.server.webflux")
class GatewayPropertiesLocationTest {

    private static Binder binder() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application-local", new ClassPathResource("application-local.yml"));
        sources.forEach(env.getPropertySources()::addLast);
        return new Binder(ConfigurationPropertySources.get(env));
    }

    @Test
    @DisplayName("🔴 라우트가 새 위치에서 읽힌다 — id 가 비지 않고 겹치지 않는다")
    void routesBindFromNewPrefix() throws Exception {
        GatewayProperties props = binder()
                .bind("spring.cloud.gateway.server.webflux", Bindable.of(GatewayProperties.class))
                .orElseThrow(() -> new AssertionError("spring.cloud.gateway.server.webflux 가 없다"));

        List<RouteDefinition> routes = props.getRoutes();
        assertThat(routes).hasSizeGreaterThanOrEqualTo(10);
        assertThat(routes).extracting(RouteDefinition::getId).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(routes).allSatisfy(r -> assertThat(r.getPredicates()).as(r.getId()).isNotEmpty());
        assertThat(props.getDefaultFilters()).isNotEmpty();
    }

    @Test
    @DisplayName("🔴 옛 위치(spring.cloud.gateway.routes 등)에 남은 키가 없다 — 다음 메이저에서 조용히 사라진다")
    void nothingLeftAtOldPrefix() throws Exception {
        List<String> stale = new java.util.ArrayList<>();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application-local", new ClassPathResource("application-local.yml"))) {
            if (source instanceof org.springframework.core.env.EnumerablePropertySource<?> e) {
                for (String name : e.getPropertyNames()) {
                    if (name.startsWith("spring.cloud.gateway.") && !name.startsWith("spring.cloud.gateway.server.webflux.")) {
                        stale.add(name);
                    }
                }
            }
        }
        assertThat(stale).as("spring.cloud.gateway.* → spring.cloud.gateway.server.webflux.* 로 옮길 것").isEmpty();
    }

    /**
     * 🐛 연결 풀이 백엔드가 이미 닫은 연결을 재사용해 {@code PrematureCloseException} → fallback 503 이 나던 것.
     * 쉬는 연결은 백엔드(Tomcat) keep-alive 60초보다 먼저 버려야 한다.
     */
    @Test
    @DisplayName("🔴 연결 풀은 쉬는 연결을 백엔드 keep-alive(60초)보다 먼저 버린다")
    void poolDropsIdleConnectionsBeforeBackendDoes() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application-local", new ClassPathResource("application-local.yml"))
                .forEach(env.getPropertySources()::addLast);
        // Binder.get 은 ${…:기본값} 을 풀어 준다(위의 binder() 는 풀지 않는다)
        org.springframework.cloud.gateway.config.HttpClientProperties http = Binder.get(env)
                .bind("spring.cloud.gateway.server.webflux.httpclient",
                        Bindable.of(org.springframework.cloud.gateway.config.HttpClientProperties.class))
                .orElseThrow(() -> new AssertionError("httpclient 설정이 없다"));

        assertThat(http.getPool().getMaxIdleTime()).as("max-idle-time 이 없으면 무기한 — 닫힌 연결을 꺼낸다").isNotNull();
        assertThat(http.getPool().getMaxIdleTime()).isLessThan(java.time.Duration.ofSeconds(60));
        assertThat(http.getPool().getMaxLifeTime()).isNotNull();
    }
}
