package com.project.order_service.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.i18n.ClientText;

/**
 * order-service 의 서버 문구가 실제 카탈로그(classpath*:i18n/client-text.ja.txt)로 일본어가 되는지 (docs/60 §4).
 *
 * <p>ClientTextCoverageTest 는 "카탈로그에 적혀 있다" 만 본다. 여기서는 조각을 이어 만든 문구 — 재고 부족 목록, 결제 실패
 * 접두어 — 가 패턴과 조각 번역을 거쳐 끝까지 바뀌는지를 본다.
 */
@DisplayName("order-service 서버 문구 일본어")
class OrderClientTextTest {

    private static String ja(String message) {
        return ClientText.localize(message, Locale.JAPANESE);
    }

    @Test
    @DisplayName("고정 문구")
    void exactMessage() {
        assertThat(ja("장바구니가 비어 있습니다. 상품을 담은 뒤 다시 시도해 주세요."))
                .isEqualTo("カートが空です。商品を追加してからもう一度お試しください。");
        assertThat(ja("환불이 처리되었습니다.")).isEqualTo("返金が完了しました。");
    }

    @Test
    @DisplayName("재고 부족 — 남은 수량·품절이 섞인 목록도 조각마다")
    void stockShortageWithMixedList() {
        assertThat(ja("재고가 부족해 주문하지 못했습니다: 티셔츠(M) 남은 수량 1개, 바지(L) 품절. 장바구니에서 수량을 조정해 주세요."))
                .isEqualTo("在庫が不足しているため注文できませんでした：티셔츠(M) 残り1点、바지(L) 在庫切れ。カートで数量を調整してください。");
    }

    @Test
    @DisplayName("재고 부족 — 무통장입금 시작의 변형, 이름 없는 상품")
    void bankTransferShortage() {
        assertThat(ja("재고가 부족해 입금을 시작하지 못했습니다: 상품(XL) 품절. 장바구니에서 수량을 조정해 주세요."))
                .isEqualTo("在庫が不足しているためお振込手続きを開始できませんでした：商品(XL) 在庫切れ。カートで数量を調整してください。");
    }

    @Test
    @DisplayName("값이 들어간 문구와 결제 실패 접두어")
    void patternsAndPaymentPrefix() {
        assertThat(ja("배송이 완료된 주문만 구매 확정할 수 있습니다: status=PAID"))
                .isEqualTo("購入確定できるのは配送が完了した注文のみです：status=PAID");
        assertThat(ja("결제를 처리하지 못했습니다: 결제가 완료되지 않았습니다: provider=STRIPE, status=unpaid"))
                .isEqualTo("決済処理に失敗しました：決済が完了していません：provider=STRIPE, status=unpaid");
    }

    /** 🔒 포트원(PG)이 쓴 문구는 일본어 응답에 싣지 않는다 — 읽을 수 없는 한국어다. 코드만 남기고 원문은 로그에 있다(docs/60 §12). */
    @Test
    @DisplayName("포트원 거절 — 일본어에서는 PG 원문을 빼고 코드만")
    void portOneProviderTextIsDropped() {
        assertThat(ja("결제를 처리하지 못했습니다: 포트원이 조회를 거부했습니다: code=-1, message=존재하지 않는 결제정보입니다"))
                .isEqualTo("決済処理に失敗しました：PortOne が照会を拒否しました（エラーコード：-1）");
        assertThat(ja("포트원이 환불을 거부했습니다: code=1, message=이미 전액취소된 주문입니다"))
                .isEqualTo("PortOne が返金を拒否しました（エラーコード：1）");
        assertThat(ja("포트원 결제 조회 실패: payment-1 — 400 Bad Request: {\"type\":\"PAYMENT_NOT_FOUND\",\"message\":\"결제 건이 존재하지 않습니다.\"}"))
                .isEqualTo("PortOne 決済の照会に失敗しました：payment-1");
        // 한국어 요청은 그대로 — 원문이 원인을 가장 잘 말해 준다
        assertThat(ClientText.localize("포트원이 환불을 거부했습니다: code=1, message=이미 전액취소된 주문입니다", Locale.KOREAN))
                .isEqualTo("포트원이 환불을 거부했습니다: code=1, message=이미 전액취소된 주문입니다");
    }
}
