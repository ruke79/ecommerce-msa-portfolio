package com.project.coupon_service.message.consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.message.dto.request.UserCreatedRequest;
import com.project.coupon_service.config.WelcomeCouponProperties;
import com.project.coupon_service.service.CouponService;

/**
 * 가입 이벤트(user-created)가 다시 와도 정상으로 끝난다 — 이미 가입 쿠폰이 있으면 건너뛴다(CP4).
 *
 * <p>🐛 예전에는 "이미 있음"(create 가 null)을 예외로 바꿔, 커밋 전 리밸런스 · 재기동 · CDC 중복 같은 정상 재전달이 재시도 뒤
 * user-created.DLT 와 경보(KafkaDeadLetterPublished)로 끝났다.
 */
@DisplayName("가입 쿠폰 — 재전달")
class WelcomeCouponRedeliveryTest {

    @Test
    @DisplayName("🔴 이미 가입 쿠폰이 있으면(create 가 null) 예외 없이 끝난다 — DLT 로 가지 않는다")
    void redeliveryIsNotAnError() {
        CouponService coupons = mock(CouponService.class);
        when(coupons.create(any())).thenReturn(null);
        UserMessageListener listener = new UserMessageListener(coupons, new WelcomeCouponProperties());
        UserCreatedRequest created = UserCreatedRequest.builder().userId(7L).build();

        assertThatCode(() -> listener.userCreated(created)).doesNotThrowAnyException();
        verify(coupons).create(any());
    }
}
