package com.project.gatewayserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * 게이트웨이 CORS 허용 출처에 브라우저가 여는 주소({@code APP_PUBLIC_URL})가 있다.
 *
 * <p>🐛 2026-11-20 실습(shop.lab): 허용 출처가 {@code https://localhost:3000} 하나뿐이라 traefik 을 거친 채팅 WebSocket
 * 업그레이드({@code Origin: https://shop.lab})를 게이트웨이 CORS 가 403 으로 막았다 — 같은 요청에서 Origin 을 빼면 101.
 *
 * <p>[JA] ゲートウェイの CORS 許可オリジンにブラウザが開くアドレス(APP_PUBLIC_URL)がある。localhost だけで WebSocket が 403 だった。
 */
@DisplayName("gateway — CORS 허용 출처에 APP_PUBLIC_URL")
class CorsAllowedOriginTest {

    private static List<String> allowedOrigins(Map<String, Object> env) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-env", env));
        List<String> names = new ArrayList<>();
        new YamlPropertySourceLoader().load("local", new ClassPathResource("application-local.yml")).forEach(ps -> {
            environment.getPropertySources().addLast(ps);
            if (ps instanceof EnumerablePropertySource<?> e) {
                for (String n : e.getPropertyNames()) {
                    if (n.contains("globalcors") && n.matches(".*allowed-?[oO]rigins\\[\\d+]$")) {
                        names.add(n);
                    }
                }
            }
        });
        return names.stream().map(environment::getProperty).toList();
    }

    @Test
    @DisplayName("🔴 APP_PUBLIC_URL(실습 shop.lab · 운영 공개 도메인)이 허용 출처에 있다 · 개발 PC 의 localhost:3000 도 그대로")
    void includesPublicUrl() throws Exception {
        assertThat(allowedOrigins(Map.of("APP_PUBLIC_URL", "https://shop.lab")))
                .contains("https://shop.lab", "https://localhost:3000");
    }
}
