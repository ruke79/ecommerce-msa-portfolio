package com.project.order_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.project.common.dto.CartDto;
import com.project.common.dto.CartProductDto;
import com.project.order_service.client.CartServiceClient;

/**
 * 주문 품목의 상품 id 는 장바구니 <b>줄</b> id 가 아니라 <b>상품</b> id 다.
 *
 * <p>🐛 예전에는 {@code CartProductDto.id}(장바구니 줄 TSID)를 상품 id 로 저장했다. 어느 상품도 가리키지 않는 값이라 재고 예약은
 * "품절", 결제 뒤 차감은 "상품 없음 → 전량 부족 → 자동 환불", 장바구니 비우기는 불일치, 리뷰는 "구매하지 않음" 이 됐다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("주문 품목 — 상품 id")
class OrderProductIdTest {

    @Mock CartServiceClient cartServiceClient;
    @InjectMocks OrderService service;

    private static CartProductDto line(String lineId, String productId) {
        return CartProductDto.builder().id(lineId).productId(productId).name("Italian Olive Oil").size("").qty(1).build();
    }

    @Test
    @DisplayName("🔴 줄 id 가 아니라 상품 id 를 쓴다")
    void usesProductIdNotLineId() {
        assertThat(OrderDataService.requireProductId(line("786912345678901234", "1"))).isEqualTo(1L);
    }

    @Test
    @DisplayName("상품 id 가 없으면 주문을 만들지 않는다 — 줄 id 로 대신하지 않는다")
    void missingProductIdRefuses() {
        assertThatThrownBy(() -> OrderDataService.requireProductId(line("786912345678901234", null)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> OrderDataService.requireProductId(line("786912345678901234", " ")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("옛 캐시 사본(상품 id 없음)은 줄 id 로 cart 에 물어 채운다 — 있는 것은 묻지 않는다")
    void fillsFromCartOnlyWhenMissing() {
        CartProductDto old = line("786912345678901234", null);
        CartProductDto fresh = line("786912345678909999", "95");
        CartDto cart = CartDto.builder().products(List.of(old, fresh)).build();
        when(cartServiceClient.getProductId("786912345678901234")).thenReturn("1");

        service.fillMissingProductIds(cart);

        assertThat(old.getProductId()).isEqualTo("1");
        assertThat(fresh.getProductId()).isEqualTo("95");
        verify(cartServiceClient, never()).getProductId("786912345678909999");
        verify(cartServiceClient, org.mockito.Mockito.times(1)).getProductId(any());
    }
}
