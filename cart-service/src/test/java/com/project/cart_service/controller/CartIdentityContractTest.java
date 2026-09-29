package com.project.cart_service.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import com.project.cart_service.service.CartService;
import com.project.common.dto.cart.request.CartRequest;

/**
 * cart 의 신원은 <b>{@code User-Id}(토큰의 uid)</b> 하나다 (docs/48 §6-⑥).
 *
 * <p>🔴 {@code /savecart} 는 예전에 <b>본문의 email</b> 로 사용자를 찾았다 — 로그인만 하면 남의 이메일을 넣어 그 사람의
 * 장바구니를 지우고 덮어쓸 수 있었다(docs/38 §1.6). 조회 둘({@code /loadcart}·{@code /checkout})은 {@code User-Email} 을
 * 받아 user-service 에 이메일 → id 를 다시 물었다. order·coupon 의 CartServiceClient 가 이 계약의 짝이다.
 */
@DisplayName("cart — 신원은 User-Id 헤더")
class CartIdentityContractTest {

    private final CartService service = mock(CartService.class);
    private final CartController controller = new CartController(service);

    private static RequestHeader identityHeaderOf(String methodName) {
        Method m = Arrays.stream(CartController.class.getDeclaredMethods())
                .filter(x -> x.getName().equals(methodName)).findFirst().orElseThrow();
        assertThat(Arrays.stream(m.getParameters()).anyMatch(p -> p.isAnnotationPresent(RequestParam.class)))
                .as(methodName + " 는 쿼리 파라미터로 신원을 받지 않는다").isFalse();
        return Arrays.stream(m.getParameters()).map((Parameter p) -> p.getAnnotation(RequestHeader.class))
                .filter(h -> h != null && h.value().startsWith("User-")).findFirst().orElse(null);
    }

    @Test
    @DisplayName("저장·조회 셋 모두 User-Id 헤더로 신원을 읽는다 — User-Email 이 아니다")
    void everyEndpointReadsUserId() {
        for (String m : new String[] { "saveCart", "loadCart", "getCart" }) {
            RequestHeader h = identityHeaderOf(m);
            assertThat(h).as(m).isNotNull();
            assertThat(h.value()).as(m).isEqualTo("User-Id");
        }
    }

    @Test
    @DisplayName("🔴 savecart 는 본문의 email 이 아니라 User-Id 의 장바구니를 저장한다")
    void saveCartIgnoresBodyEmail() throws Exception {
        // 옛 클라이언트(또는 공격자)가 본문에 남의 email 을 싣는다 — 받지도 않는다(필드가 없다).
        CartRequest body = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"products\":[],\"email\":\"victim@example.com\"}", CartRequest.class);
        when(service.saveCart(any(), anyLong())).thenReturn(null);

        controller.saveCart(body, 7L);

        verify(service).saveCart(body, 7L);
        assertThat(Arrays.stream(CartRequest.class.getDeclaredFields()).map(java.lang.reflect.Field::getName))
                .doesNotContain("email");
    }

    /** 🐛 email 에 @NotNull 이 붙어 있었다 — 이메일을 보내지 않는 새 화면의 저장이 @Valid 에서 전부 400 이 될 뻔했다. */
    @Test
    @DisplayName("이메일 없는 본문이 검증을 통과한다")
    void bodyWithoutEmailIsValid() throws Exception {
        CartRequest body = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"products\":[]}", CartRequest.class);
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(body)).isEmpty();
        }
    }

    @Test
    @DisplayName("User-Id 가 없으면 401 — 서비스에 닿지 않는다")
    void missingIdentityIsUnauthorized() {
        assertThat(controller.saveCart(mock(CartRequest.class), null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.loadCart(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.getCart(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(service, never()).saveCart(any(), anyLong());
        verify(service, never()).getCart(anyLong());
    }

    /** 담기·수량·삭제(docs/40 0-b) — 누구의 장바구니인가는 User-Id 뿐이다. 쿼리 파라미터는 줄을 가리킬 뿐이다. */
    @Test
    @DisplayName("담기·수량·삭제도 User-Id 헤더로 — 없으면 401, 서비스에 닿지 않는다. 잘못된 담기는 400")
    void itemEndpointsUseUserId() {
        for (String name : new String[] { "putItem", "setItemQty", "removeItem" }) {
            Method m = Arrays.stream(CartController.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            assertThat(Arrays.stream(m.getParameters()).map(p -> p.getAnnotation(RequestHeader.class))
                    .filter(h -> h != null).map(RequestHeader::value)).as(name).containsExactly("User-Id");
            assertThat(Arrays.stream(m.getParameters()).map(p -> p.getAnnotation(RequestParam.class))
                    .filter(p -> p != null).map(RequestParam::value)).as(name).noneMatch(v -> v.startsWith("user"));
        }

        var item = com.project.common.dto.ProductInfoDto.builder().id("1").qty(1).build();
        assertThat(controller.putItem(null, item).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.setItemQty(null, 1L, 2L, "M", 1).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.removeItem(null, 1L, 2L, "M").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(service, never()).putItem(anyLong(), any());

        when(service.putItem(7L, item)).thenThrow(new IllegalArgumentException("상품을 찾을 수 없습니다."));
        assertThat(controller.putItem(7L, item).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** 🐛 옛 줄은 상품 id 가 없다 — Long.toString(null) 의 NPE 가 전역 처리기의 일반 문구가 됐다. */
    @Test
    @DisplayName("줄의 상품 id 가 없거나 줄이 없으면 404 — NPE 가 아니다")
    void productIdOfLegacyLineIsNotFound() {
        when(service.getProductId("11")).thenReturn(null);
        when(service.getProductId("12")).thenThrow(new RuntimeException("Cart product not found"));
        when(service.getProductId("13")).thenReturn(5L);

        assertThat(controller.getProductId("11").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.getProductId("12").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.getProductId("13").getBody()).isEqualTo("5");
    }
}
