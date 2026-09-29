package com.project.common.dto.request;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.project.common.constants.OrderStatus;
import com.project.common.constants.PaymentStatus;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
/**
 * 결제 관련 요청 본문.
 *
 * <p>⚠️ 이름이 {@code Paypal…} 이지만 Stripe 경로도 쓴다 — 담기는 것이 "어느 주문의 어떤
 * 결제 참조" 뿐이라 제공자와 무관하기 때문이다. 이름은 다음 정리 때 바꾼다.
 *
 * <p>🔒 <b>여기 담긴 금액·결제 상태·이메일은 서버가 읽지 않는다.</b> 브라우저가 그 값을
 * 정하게 두는 것이 docs/35 결함의 출발점이었다. 서버가 읽는 것은 {@code trackingId} 와
 * 결제 참조뿐이고, 나머지는 전부 제공자에게 다시 물어 확인한다.
 */
public class PaypalPaymentRequest {

    private String orderId;

    private String userEmail;

    private String trackingId;

    private String paypalOrderId;

    /** 제공자 중립 결제 참조 — Stripe 는 Checkout 세션 id 를 여기 담는다. */
    private String providerPaymentId;

    private String orderStatus;

    private String couponName;

    private BigDecimal amounts;

    private String paymentStatus;

    private LocalDateTime createdTime;
}
