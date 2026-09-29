package com.project.common.constants;

/**
 * 결제를 실제로 처리한 곳.
 *
 * <h3>왜 {@link PaymentType} 과 따로 두는가</h3>
 *
 * <p>{@code PaymentType} 은 <b>사용자가 고른 수단</b>이다(PAYPAL · CREDIT_CARD · CASH).
 * 이 enum 은 <b>그 결제를 확인해 줄 상대</b>다. 둘은 겹쳐 보이지만 같지 않다 — 카드 결제를
 * Stripe 로 받을 수도 PayPal 로 받을 수도 있고, 그때 "무엇으로 냈나"와 "누구에게 물어봐야
 * 하나"는 다른 질문이다.
 *
 * <p>서버가 결제를 확인할 때 필요한 것은 <b>후자</b>다. 그래서 주문에 이 값을 따로 남긴다.
 */
public enum PaymentProvider {
    PAYPAL,
    STRIPE,
    /** 포트원(구 아임포트) — 국내 PG·간편결제 어그리게이터. */
    PORTONE,
    /**
     * 무통장입금 — 결제 API 가 아니라 <b>주문 상태 기계</b>다(docs/49 §11). "확인해 줄 상대" 는
     * 은행이 아니라 <b>입금을 확인한 관리자</b>고, 환불도 관리자가 계좌로 송금한 기록이다.
     * 결제수단 id 는 {@code cash} 다({@link #from} 이 이리로 보낸다).
     */
    BANK_TRANSFER;

    /** 알 수 없는 값이면 null. 외부에서 들어온 문자열을 그대로 valueOf 하지 않기 위해서다. */
    public static PaymentProvider from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().toUpperCase();
        if ("CASH".equals(v)) {
            return BANK_TRANSFER;
        }
        try {
            return valueOf(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
