package com.project.cart_service.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;

import com.project.cart_service.service.CartService;
import com.project.common.dto.ProductInfoDto;
import com.project.common.response.MessageResponse;

/**
 * 장바구니 실패의 상태 코드 — 고칠 수 있는 요청만 400, 서버 쪽 실패는 503(docs/83 §5).
 *
 * <p>🐛 2026-10-18 order 부하 측정: 정점에 cart CPU 1 코어 · DB 커넥션 대기 14 ~ 15 일 때 담기 실패 786건이 모두 400 이었다.
 *
 * <p>[JA] カートの失敗のステータス — 直せる要求だけ 400、サーバー側の失敗は 503。
 */
@DisplayName("장바구니 — 실패의 상태 코드")
class CartFailureStatusTest {

    private static final ProductInfoDto LINE = ProductInfoDto.builder().id("7").qty(1).build();

    /** 끝점마다 — 서비스가 그 예외를 던지게 하고 컨트롤러의 상태를 돌려준다. */
    private static List<Function<RuntimeException, Integer>> endpoints() {
        return List.of(
                e -> call(e, (svc, c) -> { when(svc.putItem(anyLong(), any())).thenThrow(e); return c.putItem(1L, LINE); }),
                e -> call(e, (svc, c) -> { when(svc.setItemQty(anyLong(), anyLong(), anyLong(), anyString(), anyInt())).thenThrow(e);
                    return c.setItemQty(1L, 7L, 5L, "M", 2); }),
                e -> call(e, (svc, c) -> { when(svc.removeItem(anyLong(), anyLong(), anyLong(), anyString())).thenThrow(e);
                    return c.removeItem(1L, 7L, 5L, "M"); }),
                e -> call(e, (svc, c) -> { when(svc.getCartForCheckout(anyLong())).thenThrow(e); return c.getCart(1L); }),
                e -> call(e, (svc, c) -> { when(svc.updateCart(any())).thenThrow(e); return c.updateCart(null); }));
    }

    private interface Call {
        ResponseEntity<?> apply(CartService svc, CartController controller);
    }

    private static int call(RuntimeException e, Call call) {
        CartService svc = mock(CartService.class);
        ResponseEntity<?> res = call.apply(svc, new CartController(svc));
        return res.getStatusCode().value();
    }

    @Test
    @DisplayName("🔴 DB 커넥션 대기 시간 초과 · catalog 호출 실패 같은 서버 쪽 실패는 503 — 담기 · 수량 · 빼기 · 체크아웃 · 갱신 모두")
    void serverSideFailuresAre503() {
        for (Function<RuntimeException, Integer> endpoint : endpoints()) {
            assertThat(endpoint.apply(new DataAccessResourceFailureException("Connection is not available, request timed out after 5000ms")))
                    .isEqualTo(503);
            assertThat(endpoint.apply(new IllegalStateException("catalog 호출 실패"))).isEqualTo(503);
        }
    }

    @Test
    @DisplayName("고칠 수 있는 요청(수량 범위 · catalog 가 모르는 상품)은 그대로 400 — 문구도 그대로")
    void clientErrorsStay400() {
        for (Function<RuntimeException, Integer> endpoint : endpoints()) {
            assertThat(endpoint.apply(new IllegalArgumentException("수량은 1 ~ 1000 사이여야 합니다."))).isEqualTo(400);
        }
        CartService svc = mock(CartService.class);
        when(svc.putItem(anyLong(), any())).thenThrow(new IllegalArgumentException("수량은 1 ~ 1000 사이여야 합니다."));
        ResponseEntity<?> res = new CartController(svc).putItem(1L, LINE);
        assertThat(((MessageResponse) res.getBody()).getMessage()).isEqualTo("수량은 1 ~ 1000 사이여야 합니다.");
    }
}
