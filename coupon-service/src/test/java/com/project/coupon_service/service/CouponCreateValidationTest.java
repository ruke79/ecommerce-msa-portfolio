package com.project.coupon_service.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.coupon_service.client.CartServiceClient;
import com.project.coupon_service.client.CatalogServiceClient;
import com.project.coupon_service.client.UserServiceClient;
import com.project.coupon_service.dto.request.CouponRequest;
import com.project.coupon_service.message.producer.CouponIssuedProducer;
import com.project.coupon_service.message.producer.CouponUseProducer;
import com.project.coupon_service.model.Coupon;
import com.project.coupon_service.repository.jpa.CouponRepository;
import com.project.coupon_service.repository.jpa.CouponTargetRepository;

/**
 * 쿠폰 발급의 입력 검사(CP8) — 할인율 1 ~ 100, 시작일 ≤ 종료일.
 *
 * <p>🐛 예전에는 아무 값이나 받았다: 계산기가 할인을 0 ~ 100 으로 잘라 내지만 발급 알림에는 원래 값(150%)이 실렸고, 시작일이 종료일보다
 * 늦은 쿠폰은 아무도 쓸 수 없는 행으로 남았다.
 */
@DisplayName("쿠폰 발급 — 입력 검사")
class CouponCreateValidationTest {

    private final CouponRepository coupons = mock(CouponRepository.class);
    private final CouponIssuedProducer issued = mock(CouponIssuedProducer.class);
    private final CouponService service = new CouponService(coupons, mock(CouponTargetRepository.class),
            mock(UserServiceClient.class), mock(CartServiceClient.class), mock(CatalogServiceClient.class),
            mock(CouponUseProducer.class), issued);

    @Test
    @DisplayName("🔴 할인율이 1 ~ 100 밖이면 거절한다 — 저장도 알림도 없다")
    void discountOutOfRange() {
        LocalDateTime now = LocalDateTime.now();
        assertThatThrownBy(() -> service.create(new CouponRequest("BIG", now, now.plusDays(1), 150, "7")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("할인율");
        assertThatThrownBy(() -> service.create(new CouponRequest("ZERO", now, now.plusDays(1), 0, "7")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(coupons, never()).save(any(Coupon.class));
        verify(issued, never()).publish(any());
    }

    @Test
    @DisplayName("🔴 시작일이 종료일보다 늦으면 거절한다")
    void startAfterEnd() {
        LocalDateTime now = LocalDateTime.now();
        assertThatThrownBy(() -> service.create(new CouponRequest("LATE", now.plusDays(2), now, 10, "7")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("시작일");
        verify(coupons, never()).save(any(Coupon.class));
    }
}
