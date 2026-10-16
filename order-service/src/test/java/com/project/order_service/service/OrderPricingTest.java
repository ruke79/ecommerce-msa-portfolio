package com.project.order_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.dto.CartDto;
import com.project.common.dto.CartProductDto;
import com.project.common.dto.order.request.OrderRequest;
import com.project.order_service.client.CouponServiceClient;
import com.project.order_service.domain.exception.OrderDomainException;

/**
 * 주문 금액은 <b>서버가</b> 정한다 (docs/40 #10).
 *
 * <h3>🔴 무엇이 문제였나</h3>
 *
 * <p>예전에는 요청 본문의 {@code total} 을 그대로 저장했다. 그 값은 브라우저가 계산한
 * 것이다. PayPal 검증이 있었지만 그것은 <b>결제액과 DB 의 주문 총액이 같은지</b>만 본다 —
 * 총액 자체가 브라우저에서 왔으므로 {@code total: 1} 을 보내고 1달러를 결제하면 검증은
 * <b>정상 통과했다.</b> 자물쇠는 튼튼한데 열쇠를 손님이 깎고 있었다.
 */
@DisplayName("주문 금액 산정")
class OrderPricingTest {

    private final CouponServiceClient couponServiceClient = mock(CouponServiceClient.class);
    private final OrderPricing service = new OrderPricing(couponServiceClient);

    private static CartDto cart(String total) {
        return CartDto.builder()
                .cartTotal(new BigDecimal(total))
                .products(List.of(CartProductDto.builder()
                        .id("1").productId("10").name("t").qty(1)
                        .price(new BigDecimal(total)).build()))
                .build();
    }

    /** 상품 금액 + 배송비. cart-service 의 computeCartTotal 이 만드는 모양 그대로다. */
    private static CartDto cartWithShipping(String merchandise, String shipping) {
        return CartDto.builder()
                .cartTotal(new BigDecimal(merchandise).add(new BigDecimal(shipping)))
                .products(List.of(CartProductDto.builder()
                        .id("1").productId("10").name("t").qty(1)
                        .price(new BigDecimal(merchandise))
                        .shipping(new BigDecimal(shipping))
                        .build()))
                .build();
    }

    @Test
    @DisplayName("쿠폰이 없으면 장바구니 총액이 그대로 주문 총액이다")
    void noCouponUsesTheCartTotal() {
        OrderPricing.Amounts amounts = service.priceOrder(cart("100"), null, 7L);

        assertThat(amounts.total()).isEqualByComparingTo("100");
        assertThat(amounts.totalBeforeDiscount()).isEqualByComparingTo("100");
        assertThat(amounts.couponApplied()).isNull();
    }

    @Test
    @DisplayName("쿠폰이 없으면 쿠폰 서비스를 부르지 않는다")
    void noCouponNoCall() {
        service.priceOrder(cart("100"), "   ", 7L);

        verify(couponServiceClient, never()).quote(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("할인액은 쿠폰 서비스가 정한다")
    void discountComesFromTheCouponService() {
        when(couponServiceClient.quote(eq(7L), eq("WELCOME10"), any()))
                .thenReturn(new CouponServiceClient.Quote(
                        new BigDecimal("90"), new BigDecimal("10"), ""));

        OrderPricing.Amounts amounts = service.priceOrder(cart("100"), "WELCOME10", 7L);

        assertThat(amounts.total()).isEqualByComparingTo("90");
        assertThat(amounts.totalBeforeDiscount()).isEqualByComparingTo("100");
        assertThat(amounts.couponApplied()).isEqualTo("WELCOME10");
    }

    /**
     * 🔴 이 테스트가 이 변경의 핵심이다. 본문이 무엇을 주장하든 금액은 장바구니에서 온다.
     * {@code OrderRequest} 에는 이제 금액 필드가 아예 없어서 주장할 방법조차 없다.
     */
    @Test
    @DisplayName("요청 본문에는 금액을 담을 자리가 없다 — 필드를 지웠다")
    void theRequestCannotCarryAnAmount() {
        List<String> fields = java.util.Arrays.stream(OrderRequest.class.getDeclaredFields())
                .map(Field::getName)
                .toList();

        assertThat(fields)
                .as("금액 필드를 되살리면 브라우저가 주문 금액을 정하게 된다")
                .doesNotContain("total", "totalBeforeDiscount", "totalAfterDiscount");
    }

    /**
     * 쿠폰을 확인하지 못했는데 할인 없이 진행하면, 사용자는 화면에서 본 금액과 다른 금액을
     * 결제하게 된다. 조용히 더 받는 것보다 "지금은 안 된다" 가 낫다.
     */
    @Test
    @DisplayName("쿠폰 서비스가 죽으면 주문을 거절한다 — 조용히 정가로 받지 않는다")
    void anUnreachableCouponServiceRejectsTheOrder() {
        when(couponServiceClient.quote(anyLong(), anyString(), any()))
                .thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> service.priceOrder(cart("100"), "WELCOME10", 7L))
                .isInstanceOf(OrderDomainException.class);
    }

    @Test
    @DisplayName("적용되지 않는 쿠폰이면 그 사유를 그대로 전한다")
    void aRejectedCouponSurfacesItsReason() {
        when(couponServiceClient.quote(anyLong(), anyString(), any()))
                .thenReturn(new CouponServiceClient.Quote(null, null, "사용 기간이 지난 쿠폰입니다."));

        assertThatThrownBy(() -> service.priceOrder(cart("100"), "OLD", 7L))
                .isInstanceOf(OrderDomainException.class)
                .hasMessageContaining("사용 기간이 지난");
    }

    @Test
    @DisplayName("응답이 비어 있어도 주문을 거절한다")
    void aNullQuoteRejectsTheOrder() {
        when(couponServiceClient.quote(anyLong(), anyString(), any())).thenReturn(null);

        assertThatThrownBy(() -> service.priceOrder(cart("100"), "WELCOME10", 7L))
                .isInstanceOf(OrderDomainException.class);
    }

    /** 할인액이 총액을 넘는 설정 실수가 음수 결제로 이어지면 그때는 이미 돈 문제다. */
    @Test
    @DisplayName("총액은 음수가 되지 않는다")
    void theTotalNeverGoesNegative() {
        when(couponServiceClient.quote(anyLong(), anyString(), any()))
                .thenReturn(new CouponServiceClient.Quote(
                        new BigDecimal("-30"), new BigDecimal("130"), ""));

        assertThat(service.priceOrder(cart("100"), "TOOMUCH", 7L).total()).isZero();
    }

    @Test
    @DisplayName("장바구니 총액이 비어 있으면 0 이다 — 추측하지 않는다")
    void anEmptyCartTotalIsZero() {
        CartDto empty = CartDto.builder().build();

        assertThat(service.priceOrder(empty, null, 7L).total()).isZero();
    }

    /**
     * 🐛 예전에는 {@code Order.total} 이 {@code int} 라 $59.98 이 59 로 저장됐고 — 프론트가
     * 제공자에 보내는 금액도 같은 59 라 검증은 통과했지만 — <b>실제로 98센트를 덜 받았다.</b>
     * 이 테스트는 그 시절 "소수점은 버린다" 를 고정하고 있었다. V7 에서 금액 컬럼이
     * {@code numeric(19,2)} 가 됐으므로 이제 반대를 고정한다: <b>센트가 살아남는다.</b>
     */
    @Test
    @DisplayName("🐛 센트가 살아남는다 — $59.98 은 59.98 이지 59 가 아니다")
    void centsAreKept() {
        assertThat(service.priceOrder(cart("59.98"), null, 7L).total()).isEqualByComparingTo("59.98");
        assertThat(service.priceOrder(cart("59.01"), null, 7L).total()).isEqualByComparingTo("59.01");
    }

    /** 컬럼은 둘째 자리까지다. 쿠폰 할인율이 만드는 셋째 자리(5.998)는 반올림한다. */
    @Test
    @DisplayName("셋째 자리는 반올림한다 — 컬럼이 numeric(19,2) 다")
    void thirdDecimalIsRounded() {
        when(couponServiceClient.quote(anyLong(), anyString(), any()))
                .thenReturn(new CouponServiceClient.Quote(
                        new BigDecimal("53.982"), new BigDecimal("5.998"), ""));

        OrderPricing.Amounts amounts = service.priceOrder(cart("59.98"), "TEN", 7L);

        assertThat(amounts.total()).isEqualByComparingTo("53.98");
        assertThat(amounts.total().scale()).isEqualTo(2);
        assertThat(amounts.totalBeforeDiscount().scale()).isEqualTo(2);
    }

    // ── 배송비 분리 ──────────────────────────────────────────────────────────

    /**
     * 🐛 {@code Order.shippingPrice} 는 컬럼이 있는데 <b>한 번도 채워진 적이 없었다</b>
     * (항상 0). 장바구니가 {@code price×qty + shipping} 으로 총액에 합산해 버리고 항목에는
     * 남기지 않아서, 주문이 아는 것은 "배송비가 포함된 총액" 하나뿐이었기 때문이다.
     *
     * <p>그래서 결제 화면도 배송비를 따로 보여 줄 수 없었다 — 사용자는 자기가 배송비를
     * 얼마 내는지 <b>주문하는 순간까지 몰랐다.</b>
     */
    @Test
    @DisplayName("배송비를 총액에서 분리해 낸다")
    void shippingIsSeparated() {
        OrderPricing.Amounts amounts = service.priceOrder(cartWithShipping("10000", "3000"), null, 7L);

        assertThat(amounts.totalBeforeDiscount()).isEqualByComparingTo("13000");
        assertThat(amounts.shippingPrice()).isEqualByComparingTo("3000");
        assertThat(amounts.merchandise()).isEqualByComparingTo("10000");
    }

    /** 상품 금액 + 배송비 = 총액. 어긋나면 화면의 세 줄이 맞지 않는다. */
    @Test
    @DisplayName("상품 금액과 배송비를 더하면 총액이다")
    void thePartsAddUpToTheWhole() {
        OrderPricing.Amounts amounts = service.priceOrder(cartWithShipping("24500", "2500"), null, 7L);

        assertThat(amounts.merchandise().add(amounts.shippingPrice()))
                .isEqualByComparingTo(amounts.totalBeforeDiscount());
    }

    /**
     * 옛 장바구니 캐시에는 shipping 필드가 없어 {@code null} 로 온다. 배송비를 <b>없는
     * 것으로</b> 보는 쪽이 안전하다 — 반대로 추측하면 상품 금액이 실제보다 작게 표시된다.
     */
    @Test
    @DisplayName("배송비가 비어 있으면 0으로 본다 — 추측하지 않는다")
    void missingShippingIsZero() {
        OrderPricing.Amounts amounts = service.priceOrder(cart("10000"), null, 7L);

        assertThat(amounts.shippingPrice()).isZero();
        assertThat(amounts.merchandise()).isEqualByComparingTo("10000");
    }

    /** 쿠폰은 배송비를 깎지 않으므로 배송비는 할인 전후로 같다. */
    @Test
    @DisplayName("쿠폰을 적용해도 배송비는 그대로다")
    void shippingSurvivesTheDiscount() {
        when(couponServiceClient.quote(anyLong(), anyString(), any()))
                .thenReturn(new CouponServiceClient.Quote(
                        new BigDecimal("12000"), new BigDecimal("1000"), ""));

        OrderPricing.Amounts amounts =
                service.priceOrder(cartWithShipping("10000", "3000"), "WELCOME10", 7L);

        assertThat(amounts.shippingPrice()).isEqualByComparingTo("3000");
        assertThat(amounts.total()).isEqualByComparingTo("12000");
        assertThat(amounts.totalBeforeDiscount()).isEqualByComparingTo("13000");
    }

    @Test
    @DisplayName("줄이 여럿이면 배송비도 합산한다")
    void shippingAddsUpAcrossLines() {
        CartDto cart = CartDto.builder()
                .cartTotal(new BigDecimal("26000"))
                .products(List.of(
                        CartProductDto.builder().id("1").productId("10").qty(1)
                                .price(new BigDecimal("10000")).shipping(new BigDecimal("3000")).build(),
                        CartProductDto.builder().id("2").productId("20").qty(1)
                                .price(new BigDecimal("10000")).shipping(new BigDecimal("3000")).build()))
                .build();

        assertThat(service.priceOrder(cart, null, 7L).shippingPrice()).isEqualByComparingTo("6000");
    }

    /**
     * 🔴 CP1 — 견적에는 품목을 만든 그 장바구니를 싣는다. coupon 이 장바구니를 다시 읽으면, 그 사이 다른 탭에서 바뀐 장바구니의 금액이
     * 이 주문에 청구됐다(품목 $1010 · 결제 $9).
     */
    @Test
    @DisplayName("🔴 견적에는 주문이 읽은 그 장바구니를 싣는다")
    void quoteCarriesTheOrdersCart() {
        CartDto ordered = cart("100");
        when(couponServiceClient.quote(eq(7L), eq("WELCOME10"), any()))
                .thenReturn(new CouponServiceClient.Quote(new BigDecimal("90"), new BigDecimal("10"), ""));

        service.priceOrder(ordered, "WELCOME10", 7L);

        verify(couponServiceClient).quote(eq(7L), eq("WELCOME10"), same(ordered));
    }
}

