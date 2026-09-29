package com.project.common.message.dto.request;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.project.common.constants.OrderStatus;
import com.project.common.constants.PaymentProvider;
import com.project.common.constants.PaymentStatus;
import com.project.common.constants.PaymentType;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.io.Serializable;

@Setter
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class PaymentRequest implements Serializable {

    private Long orderId;
    
    private Long customerId;

    private String trackingId;

    // 옛 PayPal 전용 필드(paypalOrderId)는 없다 — 참조는 providerPaymentId 하나다(스키마 v4, docs/49 §3). 옛 JSON 이 보내도
    // ignoreUnknown 으로 버린다.

    /** 이 결제를 확인해 준 곳. */
    private PaymentProvider paymentProvider;

    /** 제공자가 확인해 준 결제 참조 — PayPal 이면 PayPal 주문 ID, Stripe 면 Checkout 세션 ID. */
    private String providerPaymentId;

    private String couponName;

    private BigDecimal amounts;

    private OrderStatus orderStatus;

    private PaymentType paymentType;

    private PaymentStatus paymentStatus;

    private LocalDateTime createdAt;

    // 사용자 액세스 토큰은 싣지 않는다(docs/54 §1.7). pay-service 는 존재만 확인했을 뿐 검증하지도 쓰지도 않았고,
    // 토큰은 아웃박스 행·토픽 보존 기간 동안 평문으로 남았다.

}
