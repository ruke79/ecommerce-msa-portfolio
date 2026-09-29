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

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;

import com.project.coupon_service.client.CartServiceClient;
import com.project.coupon_service.client.CatalogServiceClient;
import com.project.coupon_service.client.UserServiceClient;
import com.project.coupon_service.controller.CouponController;
import com.project.coupon_service.dto.request.CouponApplyRequest;
import com.project.coupon_service.dto.response.CouponResponse;
import com.project.coupon_service.message.producer.CouponIssuedProducer;
import com.project.coupon_service.message.producer.CouponUseProducer;
import com.project.coupon_service.repository.jpa.CouponRepository;
import com.project.coupon_service.repository.jpa.CouponTargetRepository;

/**
 * 쿠폰 적용은 <b>요청한 사람의</b> 쿠폰에만 닿아야 한다 (BOLA — www-security 시나리오 10).
 *
 * <p>예전에는 본문의 {@code userEmail} 로 사용자를 찾았다. 로그인만 돼 있으면 본문에 남의
 * 이메일을 넣어 (1) 그 사람이 미사용 쿠폰을 가졌는지 알아내고, (2) 그 할인율로 계산된
 * 금액을 받고, (3) 그 사람 id 로 쿠폰 사용 이벤트를 발행시킬 수 있었다.
 *
 * <p>이 테스트는 "본문에 남의 이메일을 넣어도 <b>검증된 신원</b>으로만 조회한다"를 고정한다.
 */
class CouponApplyOwnershipTest {

    private final CouponRepository couponRepository = mock(CouponRepository.class);
    private final CouponTargetRepository couponTargetRepository = mock(CouponTargetRepository.class);
    private final UserServiceClient userServiceClient = mock(UserServiceClient.class);
    // 제약 있는 쿠폰만 부른다. 이 테스트의 쿠폰은 존재하지 않으므로 여기까지 오지 않는다.
    private final CartServiceClient cartServiceClient = mock(CartServiceClient.class);
    private final CatalogServiceClient catalogServiceClient = mock(CatalogServiceClient.class);
    private final CouponUseProducer couponUseProducer = mock(CouponUseProducer.class);
    // 쿠폰 발급 알림 발행자. 이 테스트는 적용 경로만 보므로 호출되지 않는다.
    private final CouponIssuedProducer couponIssuedProducer = mock(CouponIssuedProducer.class);

    private final CouponService service = new CouponService(
            couponRepository, couponTargetRepository, userServiceClient,
            cartServiceClient, catalogServiceClient, couponUseProducer, couponIssuedProducer);

    @Test
    @DisplayName("🔴 본문에 남의 이메일을 실어도 검증된 신원(User-Id)의 쿠폰만 본다 — user-service 도 부르지 않는다")
    void bodyEmailIsIgnored_verifiedIdentityWins() throws Exception {
        // 옛 클라이언트(또는 공격자)가 본문에 userEmail 을 싣는다 — 필드가 없어 읽히지도 않는다.
        CouponApplyRequest request = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"couponName\":\"WELCOME10\",\"totalPrice\":100.00,\"userEmail\":\"victim@example.com\"}",
                CouponApplyRequest.class);
        when(couponRepository.findByNameAndUserIdAndCouponStatus(
                any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(java.util.Optional.empty());

        CouponResponse response = service.applyCoupon(request, 7L);

        // 쿠폰은 **검증된 id** 로만 찾는다. 이메일 → id 왕복이 없다.
        verify(couponRepository).findByNameAndUserIdAndCouponStatus(eq("WELCOME10"), eq(7L), any());
        verify(userServiceClient, never()).findUserByEmail(anyString());
        // 남의 쿠폰이 없으니 할인도 없다 — 존재 여부조차 새지 않는다.
        assertThat(response.getMessage()).isNotBlank();
        verify(couponUseProducer, never()).publish(any());
        assertThat(java.util.Arrays.stream(CouponApplyRequest.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)).doesNotContain("userEmail");
    }

    @Test
    @DisplayName("컨트롤러는 신원을 User-Id 헤더에서 받는다 — 본문·이메일로 되돌리지 못하게")
    void controllerTakesIdentityFromHeader() throws Exception {
        // 시그니처를 고정한다. 컴파일 단계에서 걸리지 않는 회귀라 여기서 잡는다(docs/48 §6-⑥).
        Method m = CouponController.class.getDeclaredMethod(
                "applyCoupon", CouponApplyRequest.class, Long.class);

        RequestHeader header = null;
        for (var ann : m.getParameterAnnotations()[1]) {
            if (ann instanceof RequestHeader rh) {
                header = rh;
            }
        }
        assertThat(header)
                .as("두 번째 인자는 @RequestHeader(\"User-Id\") 이어야 한다")
                .isNotNull();
        assertThat(header.value().isEmpty() ? header.name() : header.value())
                .isEqualTo("User-Id");
    }

    /** 나머지 사용자 엔드포인트(쿠폰 목록·기획전 받기)도 User-Id 로 — User-Email 을 받는 곳이 남으면 id 왕복이 되살아난다. */
    @Test
    @DisplayName("쿠폰 목록·기획전 받기도 User-Id 헤더로 신원을 읽는다")
    void otherUserEndpointsReadUserId() throws Exception {
        for (Method m : new Method[] {
                CouponController.class.getDeclaredMethod("getNotUsedCoupons", Long.class),
                CouponController.class.getDeclaredMethod("claimEventCoupon", String.class, Long.class) }) {
            RequestHeader h = java.util.Arrays.stream(m.getParameters())
                    .map(p -> p.getAnnotation(RequestHeader.class)).filter(java.util.Objects::nonNull)
                    .findFirst().orElseThrow();
            assertThat(h.value()).as(m.getName()).isEqualTo("User-Id");
        }
    }
}
