package com.project.coupon_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.project.common.constants.coupon.CouponStatus;
import com.project.common.dto.CartDto;
import com.project.common.dto.CartProductDto;
import com.project.coupon_service.client.CartServiceClient;
import com.project.coupon_service.client.CatalogServiceClient;
import com.project.coupon_service.client.UserServiceClient;
import com.project.coupon_service.dto.request.CouponApplyRequest;
import com.project.coupon_service.dto.response.CouponResponse;
import com.project.coupon_service.message.producer.CouponIssuedProducer;
import com.project.coupon_service.message.producer.CouponUseProducer;
import com.project.coupon_service.model.Coupon;
import com.project.coupon_service.repository.jpa.CouponRepository;
import com.project.coupon_service.repository.jpa.CouponTargetRepository;

/**
 * 할인의 기준 금액은 <b>서버가 읽은 장바구니</b>다 (docs/40 #10).
 *
 * <p>예전에는 제약 없는 쿠폰이 요청 본문의 {@code totalPrice} 를 그대로 믿었다. 그 값은
 * 브라우저가 적는다 — 즉 "내 장바구니는 1,000,000원이다" 라고 주장하면 10% 쿠폰이
 * 100,000원을 깎아 줬다. 그 할인액이 주문 총액 계산에 그대로 들어간다.
 *
 * <p>여기서 고정하는 것 둘:
 * <ol>
 *   <li>기준 금액은 장바구니에서만 온다 — 본문 값은 무시한다</li>
 *   <li>{@code quote} 는 <b>쿠폰을 소모하지 않는다</b> — 주문 생성이 쿠폰을 써 버리면
 *       결제 실패나 이탈 때 쓰지도 않은 쿠폰이 사라진다</li>
 * </ol>
 */
@DisplayName("쿠폰 견적")
class CouponQuoteTest {

    private final CouponRepository couponRepository = mock(CouponRepository.class);
    private final CouponTargetRepository couponTargetRepository = mock(CouponTargetRepository.class);
    private final UserServiceClient userServiceClient = mock(UserServiceClient.class);
    private final CartServiceClient cartServiceClient = mock(CartServiceClient.class);
    private final CatalogServiceClient catalogServiceClient = mock(CatalogServiceClient.class);
    private final CouponUseProducer couponUseProducer = mock(CouponUseProducer.class);
    private final CouponIssuedProducer couponIssuedProducer = mock(CouponIssuedProducer.class);

    private final CouponService service = new CouponService(
            couponRepository, couponTargetRepository, userServiceClient,
            cartServiceClient, catalogServiceClient, couponUseProducer, couponIssuedProducer);

    @BeforeEach
    void setUp() {
        Coupon coupon = Coupon.builder()
                .couponId(1L)
                .name("WELCOME10")
                .discount(10)
                .userId(7L)
                .couponStatus(CouponStatus.NOT_USED)
                .startDate(LocalDateTime.now().minusDays(1))
                .endDate(LocalDateTime.now().plusDays(30))
                .build();
        when(couponRepository.findByNameAndUserIdAndCouponStatus(any(), anyLong(), any()))
                .thenReturn(Optional.of(coupon));
    }

    private void cartWorth(String total) {
        CartDto cart = CartDto.builder()
                .cartTotal(new BigDecimal(total))
                .products(List.of(CartProductDto.builder()
                        .id("1").productId("10").name("t").qty(1)
                        .price(new BigDecimal(total)).build()))
                .build();
        when(cartServiceClient.getCart(anyLong())).thenReturn(ResponseEntity.ok(cart));
    }

    /**
     * 🔴 이 테스트가 핵심이다. 본문은 100만원을 주장하지만 장바구니는 10만원이다 —
     * 깎이는 금액은 1만원이어야 한다.
     */
    @Test
    @DisplayName("본문이 주장하는 금액이 아니라 장바구니 총액으로 계산한다")
    void theBodyAmountIsIgnored() {
        cartWorth("100000");

        CouponApplyRequest request = new CouponApplyRequest();
        request.setCouponName("WELCOME10");
        request.setTotalPrice(new BigDecimal("1000000"));   // 공격자가 적어 넣은 값

        CouponResponse response = service.applyCoupon(request, 7L);

        assertThat(response.getDiscountAmount()).isEqualByComparingTo("10000");
        assertThat(response.getTotalAfterDiscount()).isEqualByComparingTo("90000");
    }

    /**
     * 🔴 장바구니를 못 읽었을 때 본문 값으로 대신하면, 장바구니 서비스를 잠시 막는 것만으로
     * 원하는 금액을 부를 수 있다. 실패는 실패로 끝내야 한다.
     */
    @Test
    @DisplayName("장바구니를 못 읽으면 적용하지 않는다 — 본문 값으로 대신하지 않는다")
    void anUnreadableCartRejects() {
        when(cartServiceClient.getCart(anyLong())).thenThrow(new RuntimeException("down"));

        CouponApplyRequest request = new CouponApplyRequest();
        request.setCouponName("WELCOME10");
        request.setTotalPrice(new BigDecimal("1000000"));

        CouponResponse response = service.applyCoupon(request, 7L);

        assertThat(response.getMessage()).isNotBlank();
        assertThat(response.getTotalAfterDiscount()).isNull();
        verify(couponUseProducer, never()).publish(any());
    }

    @Test
    @DisplayName("빈 장바구니에는 적용하지 않는다")
    void anEmptyCartRejects() {
        when(cartServiceClient.getCart(anyLong()))
                .thenReturn(ResponseEntity.ok(CartDto.builder().products(List.of()).build()));

        CouponApplyRequest request = new CouponApplyRequest();
        request.setCouponName("WELCOME10");

        assertThat(service.applyCoupon(request, 7L).getMessage())
                .isNotBlank();
    }

    @Test
    @DisplayName("견적은 같은 금액을 낸다")
    void quoteMatchesApply() {
        cartWorth("100000");

        CouponResponse quoted = service.quote("WELCOME10", 7L);

        assertThat(quoted.getTotalAfterDiscount()).isEqualByComparingTo("90000");
        assertThat(quoted.getDiscountAmount()).isEqualByComparingTo("10000");
    }

    /**
     * 🔴 주문 생성이 쿠폰을 "사용" 처리하면, 결제가 실패하거나 사용자가 결제 화면에서
     * 이탈했을 때 쓰지도 않은 쿠폰이 소모된다. 견적은 읽기만 한다.
     */
    @Test
    @DisplayName("견적은 쿠폰 사용 이벤트를 발행하지 않는다")
    void quoteHasNoSideEffect() {
        cartWorth("100000");

        service.quote("WELCOME10", 7L);

        verify(couponUseProducer, never()).publish(any());
    }

    /** 적용은 예전처럼 사용 이벤트를 발행한다 — 견적만 조용한 것이지 적용까지 바뀌면 안 된다. */
    @Test
    @DisplayName("적용은 여전히 쿠폰 사용 이벤트를 발행한다")
    void applyStillPublishes() {
        cartWorth("100000");

        CouponApplyRequest request = new CouponApplyRequest();
        request.setCouponName("WELCOME10");

        service.applyCoupon(request, 7L);

        verify(couponUseProducer).publish(any());
    }

    /** 거절된 계산으로 사용 처리를 하면 쓰지도 못한 쿠폰이 소모된다. */
    @Test
    @DisplayName("적용에 실패하면 사용 이벤트를 발행하지 않는다")
    void aRejectedApplyPublishesNothing() {
        when(couponRepository.findByNameAndUserIdAndCouponStatus(any(), anyLong(), any()))
                .thenReturn(Optional.empty());

        CouponApplyRequest request = new CouponApplyRequest();
        request.setCouponName("NOPE");

        service.applyCoupon(request, 7L);

        verify(couponUseProducer, never()).publish(any());
    }

    /**
     * 신원은 게이트웨이가 검증한 사용자 id 다. 계산하는 동안 user-service 에 묻지 않는다 — 예전에는 요청마다 이메일로
     * id 를 다시 물었고, 그 호출에 액세스 토큰이 URL 쿼리로 실렸다(docs/48 §6-⑥).
     */
    @Test
    @DisplayName("적용은 사용자 id 로 바로 쿠폰을 찾는다 — user-service 를 부르지 않는다")
    void applyUsesTheUserIdDirectly() {
        cartWorth("100000");

        CouponApplyRequest request = new CouponApplyRequest();
        request.setCouponName("WELCOME10");
        service.applyCoupon(request, 7L);

        verify(couponRepository).findByNameAndUserIdAndCouponStatus(eq("WELCOME10"), eq(7L), any());
        verify(userServiceClient, never()).findUserByEmail(anyString());
    }

    /** 견적도 사용자 id 로만 — 이메일로 id 를 찾던 대체 경로는 전환 릴리스와 함께 지웠다. 신원이 없으면 거절한다. */
    @Test
    @DisplayName("견적: 사용자 id 가 없으면 쿠폰 없음과 같은 응답이다 — 이메일로 찾지 않는다")
    void quoteWithoutAUserIdRejects() {
        assertThat(service.quote("WELCOME10", null).getMessage()).isNotBlank();
        verify(couponRepository, never()).findByNameAndUserIdAndCouponStatus(any(), anyLong(), any());
        verify(userServiceClient, never()).findUserByEmail(anyString());
    }

    /**
     * 🔴 CP1 — 주문이 실어 보낸 장바구니로 계산한다. cart-service 를 다시 읽지 않는다.
     *
     * <p>🐛 예전에는 다시 읽었다: 주문이 품목을 만든 뒤(10만원) 다른 탭에서 비싼 줄을 빼면 여기서는 1만원 장바구니로 계산해 9천원이
     * 청구됐다 — 품목은 10만원 어치, 결제는 9천원. 결제 검증은 주문 총액과만 비교하므로 통과했다.
     */
    @Test
    @DisplayName("🔴 주문이 실어 보낸 장바구니로 계산한다 — 그 사이 바뀐 장바구니를 다시 읽지 않는다")
    void quoteUsesTheOrdersCart() {
        cartWorth("10000");   // 다른 탭에서 줄을 뺀 뒤의 장바구니
        CartDto ordered = CartDto.builder()
                .cartTotal(new BigDecimal("100000"))
                .products(List.of(CartProductDto.builder().id("1").productId("10").name("t").qty(1)
                        .price(new BigDecimal("100000")).build()))
                .build();

        CouponResponse quoted = service.quote("WELCOME10", 7L, ordered);

        assertThat(quoted.getTotalAfterDiscount()).isEqualByComparingTo("90000");
        assertThat(quoted.getDiscountAmount()).isEqualByComparingTo("10000");
        verify(cartServiceClient, never()).getCart(anyLong());
    }

    @Test
    @DisplayName("장바구니가 실리지 않았으면(옛 order) 예전처럼 cart-service 에서 읽는다")
    void quoteWithoutCartFetches() {
        cartWorth("100000");

        assertThat(service.quote("WELCOME10", 7L, null).getTotalAfterDiscount()).isEqualByComparingTo("90000");
        assertThat(service.quote("WELCOME10", 7L, CartDto.builder().products(List.of()).build()).getTotalAfterDiscount())
                .isEqualByComparingTo("90000");
        verify(cartServiceClient, org.mockito.Mockito.times(2)).getCart(7L);
    }
}

