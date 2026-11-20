package com.project.order_service.payment;

import java.math.BigDecimal;

import com.project.common.constants.PaymentProvider;

/**
 * 제공자가 돌려준 환불 결과 — 제공자 중립.
 *
 * <p>[JA] プロバイダが返した返金結果 — プロバイダ中立。
 *
 * @param refundId  제공자의 환불 참조. 영수증·분쟁 대응에 쓰이므로 주문에 저장한다
 *                  [JA] プロバイダの返金参照。領収書・紛争対応に使うので注文に保存する
 * @param accepted  제공자가 환불을 <b>받아들였는가</b>. 카드 환불은 보통 즉시 완료지만
 *                  PayPal 은 {@code PENDING} 으로 답할 수 있다 — 받아들여졌으면 돈은
 *                  돌아간다. 거절(실패)만 false 다
 *                  [JA] プロバイダが返金を<b>受け付けたか</b>。カード返金は通常即時完了だが、
 *                       PayPal は {@code PENDING} で答えることがある — 受け付けられていればお金は
 *                       戻る。拒絶(失敗)だけが false である
 * @param rawStatus 제공자가 준 상태 문자열 그대로 — 로그·디버깅용
 *                  [JA] プロバイダが返した状態文字列そのまま — ログ・デバッグ用
 * @param amount    제공자가 환불했다고 말한 금액. 호출자가 주문 총액과 대조한다
 *                  [JA] プロバイダが返金したと言った金額。呼び出し側が注文総額と照合する
 * @param currency  대문자 ISO 코드
 *                  [JA] 大文字の ISO コード
 */
public record RefundResult(
        PaymentProvider provider,
        String refundId,
        boolean accepted,
        String rawStatus,
        BigDecimal amount,
        String currency) {
}
