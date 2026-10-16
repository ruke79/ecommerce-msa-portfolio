package com.project.cart_service.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.project.common.exception.ClientMessage;
import com.project.common.exception.UpstreamCallException;

import feign.Request;
import feign.Response;

/**
 * cart 의 Feign 오류 디코더 — 상류 실패가 손님 화면에 상류의 내부 문구로 나가지 않고, 어떤 상태에서도 던질 예외가 있다.
 *
 * <p>[JA] cart の Feign エラーデコーダー — 上流の失敗が上流の内部文言として客に出ず、どのステータスでも投げる例外がある。
 */
@DisplayName("cart · Feign 오류 디코더")
class FeignErrorDecoderTest {

    private final FeignErrorDecoder decoder = new FeignErrorDecoder(new MockEnvironment());

    private static Response response(int status, String body) {
        Request request = Request.create(Request.HttpMethod.GET, "https://catalog-service/api/product/info",
                Map.of(), null, StandardCharsets.UTF_8, null);
        return Response.builder().status(status).reason("x").request(request).headers(Map.of())
                .body(body, StandardCharsets.UTF_8).build();
    }

    @Test
    @DisplayName("🔴 503 — 상류 본문이 예외 문구에 없고, 응답 문구는 일반 문구다")
    void upstreamBodyDoesNotLeak() {
        Exception e = decoder.decode("CatalogServiceClient#getProductsInfo(List)",
                response(503, "{\"message\":\"Connection refused: postgres.postgres.svc:5432\"}"));
        assertThat(e).isInstanceOf(UpstreamCallException.class);
        assertThat(e.getMessage()).doesNotContain("postgres");
        assertThat(ClientMessage.of(e)).isEqualTo(ClientMessage.DEFAULT);
    }

    @Test
    @DisplayName("🔴 400 에도 예외를 돌려준다 — 예전에는 null 이라 Feign 이 던질 것이 없어 NPE(500)였다")
    void badRequestIsNotNull() {
        assertThat(decoder.decode("CatalogServiceClient#getProductsInfo(List)", response(400, "{}")))
                .isInstanceOf(UpstreamCallException.class);
    }
}
