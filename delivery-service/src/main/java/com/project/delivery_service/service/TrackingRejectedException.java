package com.project.delivery_service.service;

/**
 * 송장을 등록할 수 없는 배송 · 상자 — 이미 발송됐거나 취소됐다(관리자 화면에 409).
 *
 * <p>[JA] 送り状を登録できない配送・箱 — すでに発送済みかキャンセル済み(管理画面に 409)。
 */
public class TrackingRejectedException extends RuntimeException {

    private final String reason;

    public TrackingRejectedException(String reason) {
        super(reason);
        this.reason = reason;
    }

    /** 관리자 화면에 보여 줄 고정 문구 — 이 클래스가 만든 문장뿐이다(외부 예외 메시지가 아니다). */
    public String reason() {
        return reason;
    }
}
