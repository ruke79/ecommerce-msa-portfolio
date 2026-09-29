package com.project.chat_service.constants;

/**
 * 문의 유형 — 고객이 상담을 열 때 고른다(docs/58 §11). 데스크가 유형별로 거르고, 상담원이 무엇부터 볼지 안다.
 *
 * <p>V8 의 CHECK 제약과 같은 목록이어야 한다. 모르는 값이 오면 {@link #OTHER} 로 받는다 — 화면이 새 유형을 먼저
 * 배포해도 문의가 막히지 않게.
 */
public enum SupportTopic {
    ORDER, DELIVERY, PAYMENT, PRODUCT, ACCOUNT, OTHER;

    /** 요청의 문자열 → 유형. null · 빈 값 · 모르는 값은 OTHER. */
    public static SupportTopic parse(String value) {
        if (value == null || value.isBlank()) {
            return OTHER;
        }
        try {
            return valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return OTHER;
        }
    }
}
