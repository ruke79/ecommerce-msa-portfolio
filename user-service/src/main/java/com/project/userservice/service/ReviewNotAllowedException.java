package com.project.userservice.service;

import com.project.common.domain.exception.DomainException;

/**
 * 리뷰를 받을 수 없다 — 받은 상품이 아니거나(403), 구매 여부를 확인하지 못했다(503). docs/57 §13.
 */
public class ReviewNotAllowedException extends DomainException {

    private final boolean unavailable;

    private ReviewNotAllowedException(String message, boolean unavailable) {
        super(message);
        this.unavailable = unavailable;
    }

    static ReviewNotAllowedException notPurchased() {
        return new ReviewNotAllowedException(com.project.common.constants.StatusMessages.REVIEW_PURCHASE_REQUIRED, false);
    }

    static ReviewNotAllowedException checkUnavailable() {
        return new ReviewNotAllowedException(com.project.common.constants.StatusMessages.REVIEW_PURCHASE_CHECK_UNAVAILABLE, true);
    }

    /** 확인 자체를 못 했는가(잠시 뒤 다시) — 아니면 받은 상품이 아니다. */
    public boolean isUnavailable() {
        return unavailable;
    }
}
