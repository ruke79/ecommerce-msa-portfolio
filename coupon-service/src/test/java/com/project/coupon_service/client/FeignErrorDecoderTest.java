package com.project.coupon_service.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.project.common.exception.UpstreamCallException;

import feign.Request;
import feign.Response;

/**
 * coupon 의 Feign 오류 디코더 — 어떤 상태에도 던질 예외가 있다(CP9).
 *
 * <p>🐛 예전에는 400 · find 가 아닌 호출의 404(장바구니 · 상품 · 기획전)에 null 을 돌려줬다 — Feign 이 던질 것이 없어 NPE 가 났고, 로그에는
 * 원래 상태 대신 NPE 가 남았다.
 */
@DisplayName("coupon · Feign 오류 디코더")
class FeignErrorDecoderTest {

    private final FeignErrorDecoder decoder = new FeignErrorDecoder(new MockEnvironment());

    private static Response response(int status) {
        Request request = Request.create(Request.HttpMethod.GET, "https://cart-service/api/cart/checkout",
                Map.of(), null, StandardCharsets.UTF_8, null);
        return Response.builder().status(status).reason("x").request(request).headers(Map.of())
                .body("{\"message\":\"internal detail\"}", StandardCharsets.UTF_8).build();
    }

    @Test
    @DisplayName("🔴 400 · 404(find 아님)에도 예외를 돌려준다 — 예전에는 null 이라 NPE 였다")
    void neverNull() {
        Exception badRequest = decoder.decode("CartServiceClient#getCart(Long)", response(400));
        Exception notFound = decoder.decode("CatalogServiceClient#getEventCouponOffer(String)", response(404));

        assertThat(badRequest).isInstanceOf(UpstreamCallException.class);
        assertThat(notFound).isInstanceOf(UpstreamCallException.class);
        assertThat(badRequest.getMessage()).doesNotContain("internal detail");
    }
}
