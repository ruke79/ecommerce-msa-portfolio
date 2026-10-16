package com.project.coupon_service.message.consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.message.dto.request.CouponRollbackRequest;
import com.project.coupon_service.service.CouponService;

/**
 * 쿠폰 보상에 되돌릴 것이 없으면(쓰이지 않음 · 다른 주문이 씀) 정상으로 끝난다.
 *
 * <p>🐛 예전에는 던져서 재시도 뒤 DLT 로 갔다 — 쿠폰을 고른 주문의 결제 실패마다 DLT 한 건과 경보(KafkaDeadLetterPublished).
 * 결제 전 취소(PayPal 창 닫기)도 이제 보상을 보내므로 그대로 두면 취소마다 DLT 가 쌓인다.
 */
@DisplayName("쿠폰 보상 — 되돌릴 것 없음은 정상")
class CouponRollbackNoopTest {

    @Test
    @DisplayName("🔴 서비스가 되돌리지 않았다(null) — 예외 없이 끝난다(DLT 로 가지 않는다)")
    void nothingToRollBackIsNotAnError() {
        CouponService service = mock(CouponService.class);
        CouponRollbackRequest request = new CouponRollbackRequest(7L, "WELCOME", 100L);
        when(service.couponRollback(request)).thenReturn(null);

        assertThatCode(() -> new CouponRollbackMessageListener(service).couponRollback(request)).doesNotThrowAnyException();
        verify(service).couponRollback(request);
    }
}
