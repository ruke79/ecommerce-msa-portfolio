package com.project.userservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.exception.CustomException;
import com.project.common.exception.UpstreamCallException;

import feign.Request;
import feign.Response;

/**
 * user-service 의 Feign 오류 디코더 — 늘 예외를 돌려준다(null 이 아니다).
 * 🐛 예전에는 400 · (find 가 아닌) 404 에 null 을 돌려줘 Feign 이 throw null(부르는 쪽은 NullPointerException), 그 밖은 검사 예외였다(docs/101 §4).
 * [JA] user-service の Feign エラーデコーダー — 常に例外を返す(null ではない)。以前は null を返して NPE になっていた。
 */
@DisplayName("user Feign 오류 디코더 — 상태를 담은 UpstreamCallException, find 의 404 는 사용자 없음")
class FeignErrorDecoderTest {

    private static Response response(int status) {
        Request req = Request.create(Request.HttpMethod.GET, "http://x/y", Map.of(), new byte[0], StandardCharsets.UTF_8, null);
        return Response.builder().status(status).reason("r").request(req).headers(Map.of()).build();
    }

    private final FeignErrorDecoder decoder = new FeignErrorDecoder(null);

    @Test
    @DisplayName("🔴 400 · (find 가 아닌) 404 · 503 — 모두 상태를 담은 UpstreamCallException(null 이 아니다)")
    void alwaysReturnsUpstreamCallException() {
        for (int s : new int[] { 400, 404, 409, 503 }) {
            Exception e = decoder.decode("CatalogServiceClient#getProductInfo(String)", response(s));
            assertThat(e).as("HTTP " + s).isInstanceOf(UpstreamCallException.class);
            assertThat(((UpstreamCallException) e).getStatus()).isEqualTo(s);
        }
    }

    @Test
    @DisplayName("find 메서드의 404 는 사용자 없음(CustomException) — 그대로")
    void findNotFoundIsUserNotFound() {
        assertThatThrownBy(() -> decoder.decode("OrderServiceClient#findSomething(Long)", response(404)))
                .isInstanceOf(CustomException.class);
    }
}
