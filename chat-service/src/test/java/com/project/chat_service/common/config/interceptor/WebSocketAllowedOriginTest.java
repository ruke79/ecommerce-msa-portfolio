package com.project.chat_service.common.config.interceptor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * chat 의 SockJS · WebSocket 허용 출처({@code frontend.url})는 브라우저가 여는 주소({@code APP_PUBLIC_URL})를 따른다.
 *
 * <p>🐛 2026-11-20 실습(shop.lab): chat 만 {@code https://localhost:3000} 으로 고정돼 있어 SockJS 의 모든 전송(websocket ·
 * xhr_streaming · xhr_send)이 403 이었다 — 채팅 입력이 회색. order · user 는 이미 {@code APP_PUBLIC_URL} 을 따른다.
 *
 * <p>[JA] chat の SockJS の許可オリジンはブラウザが開くアドレス(APP_PUBLIC_URL)に従う。実習で localhost:3000 固定のため 403 だった。
 */
@DisplayName("chat — SockJS 허용 출처는 APP_PUBLIC_URL 을 따른다")
class WebSocketAllowedOriginTest {

    private static String resolve(Map<String, Object> env) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-env", env));
        new YamlPropertySourceLoader().load("local", new ClassPathResource("application-local.yml"))
                .forEach(ps -> environment.getPropertySources().addLast(ps));
        return environment.getProperty("frontend.url");
    }

    @Test
    @DisplayName("🔴 APP_PUBLIC_URL 이 있으면 그 주소 — 실습 shop.lab · 운영 공개 도메인")
    void followsPublicUrl() throws Exception {
        assertThat(resolve(Map.of("APP_PUBLIC_URL", "https://shop.lab"))).isEqualTo("https://shop.lab");
    }

    @Test
    @DisplayName("FRONTEND_URL 이 우선 · 둘 다 없으면 개발 PC 의 localhost:3000")
    void overridesAndDefault() throws Exception {
        assertThat(resolve(Map.of("FRONTEND_URL", "https://a.example", "APP_PUBLIC_URL", "https://shop.lab")))
                .isEqualTo("https://a.example");
        assertThat(resolve(Map.of())).isEqualTo("https://localhost:3000");
    }
}
