package com.project.gatewayserver.fallback;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import com.project.gatewayserver.i18n.GatewayText;

/** 게이트웨이가 직접 돌려주는 문구를 요청 언어로 (docs/60 §4). */
@DisplayName("게이트웨이 문구 — Accept-Language")
class GatewayTextTest {

    private static MockServerWebExchange exchange(String acceptLanguage) {
        MockServerHttpRequest.BaseBuilder<?> req = MockServerHttpRequest.get("/fallback");
        if (acceptLanguage != null) {
            req.header("Accept-Language", acceptLanguage);
        }
        return MockServerWebExchange.from(req);
    }

    @Test
    @DisplayName("ja 면 일본어, 없거나 ko 면 한국어, 형식이 틀리면 한국어")
    void pick() {
        assertThat(GatewayText.pick(exchange("ja"), "ko", "ja")).isEqualTo("ja");
        assertThat(GatewayText.pick(exchange("ja-JP,ja;q=0.9"), "ko", "ja")).isEqualTo("ja");
        assertThat(GatewayText.pick(exchange("ko"), "ko", "ja")).isEqualTo("ko");
        assertThat(GatewayText.pick(exchange(null), "ko", "ja")).isEqualTo("ko");
        assertThat(GatewayText.pick(exchange(";;;"), "ko", "ja")).isEqualTo("ko");
    }

    @Test
    @DisplayName("폴백 응답 — 일본어 요청이면 일본어")
    void fallbackInJapanese() {
        var response = new FallbackController().fallback(exchange("ja")).block();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isEqualTo("内部サーバーエラーが発生しました。管理者にお問い合わせください。");
    }
}
