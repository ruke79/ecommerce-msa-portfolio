package com.project.coupon_service.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.project.coupon_service.dto.request.CouponRequest;
import com.project.coupon_service.service.CouponService;

/**
 * 쿠폰 발급·수정은 관리자만 한다 (docs/54 §1.1).
 *
 * <p>게이트웨이는 로그인만 확인한다. 예전에는 로그인한 아무나 자기에게 할인율을 마음대로 정한 쿠폰을 발급할 수
 * 있었고, 주문 총액을 서버가 그 쿠폰으로 계산하므로 결제 금액 대조도 통과했다.
 */
@DisplayName("쿠폰 관리 권한")
class CouponAdminGuardTest {

    private CouponService couponService;
    private CouponController controller;

    @BeforeEach
    void setUp() {
        couponService = mock(CouponService.class);
        controller = new CouponController(couponService);
    }

    @Test
    @DisplayName("🔴 일반 사용자는 쿠폰을 발급하지 못한다 — 서비스에 닿지도 않는다")
    void userCannotIssue() {
        assertThat(controller.addCoupon(new CouponRequest(), "USER").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.addCoupon(new CouponRequest(), null).getStatusCode())
                .as("역할 헤더가 없으면 관리자가 아니다").isEqualTo(HttpStatus.FORBIDDEN);
        verify(couponService, never()).create(any());
    }

    @Test
    @DisplayName("관리자는 발급한다")
    void adminIssues() {
        controller.addCoupon(new CouponRequest(), "ROLE_ADMIN");
        verify(couponService).create(any());
    }

    @Test
    @DisplayName("일반 사용자는 쿠폰을 수정하지 못한다")
    void userCannotUpdate() {
        assertThat(controller.updateCoupon(new CouponRequest(), "USER").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(couponService, never()).update(any());
    }

    @Test
    @DisplayName("🔴 관리자의 수정은 501 — 예전에는 아무것도 하지 않고 200 을 돌려줬다(CP8)")
    void updateIsNotImplemented() {
        var response = controller.updateCoupon(new CouponRequest(), "ROLE_ADMIN");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
        verify(couponService, never()).update(any());
    }
}

