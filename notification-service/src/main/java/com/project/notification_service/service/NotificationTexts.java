package com.project.notification_service.service;

import java.util.List;

/**
 * 알림 제목·본문 — 서버가 만들어 <b>문구 그대로</b> 저장한다 (docs/60 §9).
 *
 * <p>저장은 한국어로 하고, 목록을 내줄 때 요청 언어로 바꾼다(NotificationController → ClientText, 카탈로그는
 * {@code i18n/client-text.ja.txt}). 저장할 때 번역하지 않는 이유: 받는 사람이 화면 언어를 언제든 바꾼다 — 저장된 글은 그대로
 * 두고 읽을 때 고르면 이미 쌓인 알림도 함께 바뀐다.
 *
 * <p>⚠️ 모든 모양은 {@link #FORMATS} 에 있어야 하고, 그 모양마다 카탈로그에 번역이 있어야 한다 — NotificationTextsTest 가
 * 모양마다 예시 값을 넣어 일본어로 바뀌는지(한글이 남지 않는지) 본다. 문구를 바꾸면 카탈로그의 옛 모양도 남긴다(이미 저장된
 * 알림을 위해).
 */
public final class NotificationTexts {

    private NotificationTexts() {
    }

    // ── 주문·결제 ─────────────────────────────────────────────────────────
    public static final String ORDER_PLACED_TITLE = "주문 접수";
    public static String orderPlaced(String orderId) { return "주문 " + orderId + " 이 접수되었습니다."; }

    public static final String PAYMENT_FAILED_TITLE = "결제 실패";
    public static String paymentFailed(String orderId) { return "주문 " + orderId + " 결제가 실패했습니다."; }

    public static final String PAYMENT_REFUNDED_TITLE = "환불 완료";
    public static String paymentRefunded(String orderId) { return "주문 " + orderId + " 결제가 환불되었습니다."; }

    public static final String PAYMENT_COMPLETED_TITLE = "결제 완료";
    public static String paymentCompleted(String orderId) { return "주문 " + orderId + " 결제가 완료되었습니다."; }

    public static final String PARTIAL_REFUND_TITLE = "부분 환불 완료";
    public static String partialRefund(String orderId, String amount) {
        return "주문 " + orderId + " 의 일부 품목" + (amount == null ? "" : " " + amount) + " 이(가) 환불되었습니다.";
    }

    public static final String PURCHASE_CONFIRMED_TITLE = "구매가 확정되었습니다";
    public static String purchaseAutoConfirmed(String orderId) {
        return "주문 " + orderId + " 이 배송 완료 후 확정 기한이 지나 자동으로 구매 확정되었습니다.";
    }
    public static String purchaseConfirmed(String orderId) {
        return "주문 " + orderId + " 의 구매를 확정하셨습니다. 이용해 주셔서 감사합니다.";
    }

    /** 문자·메일 제목의 주문 표시. */
    public static String orderSubject(String orderId) { return "주문 " + (orderId == null ? "" : orderId); }

    // ── 배송 ──────────────────────────────────────────────────────────────
    /**
     * 상태별 제목. 내부 상태 이름을 그대로 보여주지 않는다 — 사용자에게 "OUT_FOR_DELIVERY" 는 아무 의미가 없다. 모르는 상태는
     * 뭉뚱그린 문구로 넘어간다(새 상태가 추가돼도 알림이 깨지지 않는다).
     */
    public static String deliveryTitle(String status) {
        return switch (status == null ? "" : status) {
            case "INFO_RECEIVED"    -> "상품이 발송되었습니다";
            case "OUT_FOR_DELIVERY" -> "오늘 배송될 예정입니다";
            case "DELIVERED"        -> "배송이 완료되었습니다";
            case "CUSTOMS_HOLD"     -> "통관이 보류되었습니다";
            case "EXCEPTION"        -> "배송에 문제가 발생했습니다";
            case "RETURNED"         -> "상품이 반송되었습니다";
            case "CANCELLED"        -> "배송이 취소되었습니다";
            default                 -> "배송 상태가 변경되었습니다";
        };
    }

    public static String deliveryBody(String status, String orderId) {
        String order = orderId == null ? "주문" : "주문 " + orderId;
        return switch (status == null ? "" : status) {
            case "INFO_RECEIVED"    -> order + " 상품이 발송되었습니다. 배송 상세에서 송장번호를 확인할 수 있습니다.";
            case "OUT_FOR_DELIVERY" -> order + " 상품이 배송 출발했습니다.";
            case "DELIVERED"        -> order + " 상품이 도착했습니다.";
            case "CUSTOMS_HOLD"     -> order + " 상품이 통관 보류 중입니다. 개인통관고유부호 또는 관세 납부가 필요한지 확인해 주세요.";
            case "EXCEPTION"        -> order + " 배송 중 문제가 발생했습니다. 배송 상세에서 확인해 주세요.";
            case "RETURNED"         -> order + " 상품이 판매자에게 반송되었습니다.";
            case "CANCELLED"        -> order + " 배송이 취소되었습니다.";
            default                 -> order + " 배송 상태가 변경되었습니다.";
        };
    }

    // ── 쿠폰 ──────────────────────────────────────────────────────────────
    public static String couponTitle(String discount) {
        return discount == null ? "쿠폰이 발급되었습니다" : discount + "% 쿠폰이 발급되었습니다";
    }
    public static String couponBody(String name, String until) {
        return "쿠폰 \"" + name + "\" 이(가) 발급되었습니다."
                + (until == null ? "" : " " + until.substring(0, Math.min(until.length(), 10)) + "까지 사용할 수 있습니다.");
    }

    // ── 보안 ──────────────────────────────────────────────────────────────
    public static final String SECURITY_TITLE = "새로운 기기에서 로그인되었습니다";
    /**
     * 보안 알림 본문. 사용자가 <b>"나인가 아닌가"를 판단할 수 있어야</b> 쓸모가 있다 — 기기와 대략적인 위치가 그 재료다. 둘 다
     * 없으면 최소한 무엇을 해야 하는지는 말해 준다. IP 는 이미 마스킹된 값이 온다(user-service DeviceIdentity.maskIp).
     */
    public static String securityBody(String deviceLabel, String maskedIp) {
        StringBuilder body = new StringBuilder();
        if (deviceLabel != null && !deviceLabel.isBlank()) {
            body.append(deviceLabel);
        }
        if (maskedIp != null && !maskedIp.isBlank()) {
            body.append(body.length() > 0 ? " · " : "").append(maskedIp);
        }
        if (body.length() > 0) {
            body.append("에서 로그인되었습니다. ");
        }
        body.append("본인이 아니라면 비밀번호를 바로 변경해 주세요.");
        return body.toString();
    }

    /**
     * 리프레시 토큰 재사용 — user-service 가 그 로그인을 끊었다(user-service docs/68).
     *
     * <p>사용자에게 "토큰" · "재사용" 같은 말은 뜻이 없다. 일어난 일(로그인이 끊겼다) · 이유(다른 곳에서 쓰인 흔적) ·
     * 할 일(다시 로그인, 본인이 아니면 비밀번호 변경)만 말한다. 정상 사용자도 이 메일을 받는다 — 탈취한 쪽과
     * 구별할 수 없어서 둘 다 끊었기 때문이다. 겁주지 않되, 할 일은 분명히.
     */
    // [JA] リフレッシュトークン再利用 — ログインを切断した。ユーザーには「起きたこと・理由・やること」だけを伝える。
    public static final String SESSION_REVOKED_TITLE = "보안을 위해 로그인이 해제되었습니다";
    public static final String SESSION_REVOKED_BODY =
            "이미 사용된 로그인 정보가 다른 곳에서 다시 쓰인 흔적이 있어, 안전을 위해 해당 로그인을 종료했습니다. "
            + "다시 로그인해 주세요. 본인이 한 일이 아니라면 비밀번호를 바로 변경해 주세요.";

    /**
     * 2단계 인증을 켜거나 껐다(user-service docs/68 §14). 본인이 한 일이 아니면 누군가 로그인 상태를 쓰고 있다는 신호다 — 할 일을 분명히.
     */
    // [JA] 2 段階認証の有効化・無効化。本人でなければ誰かがログイン状態を使っている合図 — やることを明確に。
    public static final String TWO_FACTOR_ENABLED_TITLE = "2단계 인증이 켜졌습니다";
    public static final String TWO_FACTOR_ENABLED_BODY =
            "계정에 2단계 인증이 설정되었습니다. 본인이 한 일이 아니라면 바로 비밀번호를 변경하고 고객센터에 알려 주세요.";
    public static final String TWO_FACTOR_DISABLED_TITLE = "2단계 인증이 꺼졌습니다";
    public static final String TWO_FACTOR_DISABLED_BODY =
            "계정의 2단계 인증이 해제되었습니다. 본인이 한 일이 아니라면 바로 비밀번호를 변경하고 2단계 인증을 다시 켜 주세요.";

    /**
     * 로그인 보안 2차(user-service docs/68 §15). 일어난 일 · 할 일만 — 코드 · 프로바이더 내부 이름은 말하지 않는다.
     */
    // [JA] ログインセキュリティ第 2 弾。起きたこととやることだけを伝える。
    public static final String TWO_FACTOR_FAILURES_TITLE = "2단계 인증 실패가 반복되었습니다";
    public static final String TWO_FACTOR_FAILURES_BODY =
            "비밀번호는 맞았지만 2단계 인증 코드가 여러 번 틀려 잠시 로그인을 막았습니다. "
            + "본인이 아니라면 비밀번호가 다른 사람에게 알려진 것입니다 — 바로 비밀번호를 변경해 주세요.";
    public static final String PASSWORD_RESET_TITLE = "비밀번호가 재설정되었습니다";
    public static final String PASSWORD_RESET_BODY =
            "비밀번호 찾기로 새 비밀번호가 설정되어, 모든 기기의 로그인을 종료했습니다. "
            + "본인이 한 일이 아니라면 이메일 계정의 보안을 확인하고 고객센터에 알려 주세요.";
    public static final String SOCIAL_LINKED_TITLE = "새 로그인 방법이 연결되었습니다";
    public static String socialLinkedBody(String provider) {
        return (provider == null ? "소셜" : providerLabel(provider)) + " 계정으로 로그인했습니다. 본인이 한 일이 아니라면 바로 비밀번호를 변경하고 고객센터에 알려 주세요.";
    }
    public static final String UNPROVEN_PASSWORD_CLEARED_TITLE = "확인되지 않은 비밀번호를 해제했습니다";
    public static final String UNPROVEN_PASSWORD_CLEARED_BODY =
            "소셜 로그인으로 이메일 주소의 주인이 확인되어, 주소 확인 없이 설정돼 있던 비밀번호를 해제하고 모든 기기의 로그인을 종료했습니다. "
            + "비밀번호로도 로그인하려면 '비밀번호 찾기' 로 새로 설정해 주세요.";
    /** 패스키 등록 · 삭제(user-service docs/68 §20). */
    public static final String PASSKEY_ADDED_TITLE = "새 패스키가 등록되었습니다";
    public static final String PASSKEY_ADDED_BODY =
            "이 계정에 로그인할 수 있는 패스키가 새로 등록되었습니다. 본인이 한 일이 아니라면 보안 설정에서 그 패스키를 삭제하고, "
            + "비밀번호를 변경한 뒤 모든 기기에서 로그아웃해 주세요.";
    public static final String PASSKEY_REMOVED_TITLE = "패스키가 삭제되었습니다";
    public static final String PASSKEY_REMOVED_BODY =
            "이 계정의 패스키 하나가 삭제되었습니다. 본인이 한 일이 아니라면 바로 비밀번호를 변경하고 모든 기기에서 로그아웃해 주세요.";

    /** 프로바이더 표시 이름 — 사용자에게는 registrationId(google · kakao)가 아니라 서비스 이름을 보여 준다. */
    static String providerLabel(String provider) {
        return switch (provider) {
            case "google" -> "Google";
            case "github" -> "GitHub";
            case "facebook" -> "Facebook";
            case "kakao" -> "Kakao";
            case "line" -> "LINE";
            default -> provider;
        };
    }

    // ── 채팅·상담·친구 ────────────────────────────────────────────────────
    public static final String SUPPORT_REPLY_TITLE = "고객지원팀 답변";
    public static final String SUPPORT_REPLY_BODY = "상담원이 문의에 답변했습니다.";
    public static String chatTitle(String senderName) { return senderName == null ? "새 메시지" : senderName + " 님의 메시지"; }
    public static final String CHAT_BODY = "새 메시지가 도착했습니다.";
    public static String friendOnlineTitle(String nickname) { return (nickname == null ? "친구" : nickname) + " 님이 접속했습니다"; }
    public static final String FRIEND_ONLINE_BODY = "지금 대화할 수 있습니다.";

    // ── 재입고 ────────────────────────────────────────────────────────────
    public static final String RESTOCK_TITLE = "재입고 알림";
    public static final String RESTOCK_FALLBACK_PRODUCT = "신청하신 상품";
    public static String restockBody(String productName, String option) {
        return productName + " (" + option + ") 이(가) 다시 입고되었습니다. 품절되기 전에 확인해 보세요.";
    }

    /**
     * 저장될 수 있는 모든 모양 — {@code {}} 는 바뀌는 자리(주문 번호·이름·금액·날짜·기기). 테스트가 모양마다 예시 값을 넣어
     * 카탈로그로 번역되는지 본다.
     */
    public static final List<String> FORMATS = List.of(
            ORDER_PLACED_TITLE, orderPlaced("{}"),
            PAYMENT_FAILED_TITLE, paymentFailed("{}"), PAYMENT_REFUNDED_TITLE, paymentRefunded("{}"),
            PAYMENT_COMPLETED_TITLE, paymentCompleted("{}"),
            PARTIAL_REFUND_TITLE, partialRefund("{}", "{}"), partialRefund("{}", null),
            PURCHASE_CONFIRMED_TITLE, purchaseAutoConfirmed("{}"), purchaseConfirmed("{}"),
            orderSubject("{}"),
            deliveryTitle("INFO_RECEIVED"), deliveryTitle("OUT_FOR_DELIVERY"), deliveryTitle("DELIVERED"),
            deliveryTitle("CUSTOMS_HOLD"), deliveryTitle("EXCEPTION"), deliveryTitle("RETURNED"),
            deliveryTitle("CANCELLED"), deliveryTitle("OTHER"),
            deliveryBody("INFO_RECEIVED", "{}"), deliveryBody("OUT_FOR_DELIVERY", "{}"), deliveryBody("DELIVERED", "{}"),
            deliveryBody("CUSTOMS_HOLD", "{}"), deliveryBody("EXCEPTION", "{}"), deliveryBody("RETURNED", "{}"),
            deliveryBody("CANCELLED", "{}"), deliveryBody("OTHER", "{}"), deliveryBody("DELIVERED", null),
            couponTitle(null), couponTitle("{}"), couponBody("{}", null), couponBody("{}", "{}"),
            SECURITY_TITLE, securityBody("{}", null), securityBody(null, null),
            SESSION_REVOKED_TITLE, SESSION_REVOKED_BODY,
            TWO_FACTOR_ENABLED_TITLE, TWO_FACTOR_ENABLED_BODY, TWO_FACTOR_DISABLED_TITLE, TWO_FACTOR_DISABLED_BODY,
            TWO_FACTOR_FAILURES_TITLE, TWO_FACTOR_FAILURES_BODY, PASSWORD_RESET_TITLE, PASSWORD_RESET_BODY,
            SOCIAL_LINKED_TITLE, socialLinkedBody("{}"), socialLinkedBody(null),
            UNPROVEN_PASSWORD_CLEARED_TITLE, UNPROVEN_PASSWORD_CLEARED_BODY,
            PASSKEY_ADDED_TITLE, PASSKEY_ADDED_BODY, PASSKEY_REMOVED_TITLE, PASSKEY_REMOVED_BODY,
            SUPPORT_REPLY_TITLE, SUPPORT_REPLY_BODY, chatTitle(null), chatTitle("{}"), CHAT_BODY,
            friendOnlineTitle("{}"), friendOnlineTitle(null), FRIEND_ONLINE_BODY,
            RESTOCK_TITLE, restockBody("{}", "{}"), restockBody(RESTOCK_FALLBACK_PRODUCT, "{}"));
}
