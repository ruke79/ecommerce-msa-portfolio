package com.project.common.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 서버 문구를 요청 언어로 (docs/60 §4).
 */
@DisplayName("ClientText — 서버 문구 번역")
class ClientTextTest {

    private static ClientText.Catalog catalog(String text) {
        ClientText.Catalog c = new ClientText.Catalog();
        ClientText.parseInto(c, text, "test");
        c.patterns.sort(java.util.Comparator.comparingInt(ClientText.Rule::literalLength).reversed());
        return c;
    }

    static final ClientText.Catalog C = catalog("""
            # 주석
            장바구니가 비어 있습니다.
            = カートが空です。

            재고가 부족해 주문하지 못했습니다: {}. 장바구니에서 수량을 조정해 주세요.
            = 在庫不足のため注文できませんでした：{}。カートで数量を調整してください。

            {}({}) 남은 수량 {}개
            = {}（{}）残り{}点

            {}({}) 품절
            = {}（{}）売り切れ

            주문 {}의 {}을 찾을 수 없습니다.
            = {1}（注文{0}）が見つかりません。

            payment.env 는 sandbox 또는 live 여야 합니다: {}
            = -
            """);

    @Test
    @DisplayName("정확히 같은 문구")
    void exact() {
        assertThat(ClientText.translate(C, "장바구니가 비어 있습니다.", 0)).isEqualTo("カートが空です。");
    }

    @Test
    @DisplayName("🔴 바뀌는 부분이 있는 문구 — 조각도 다시 찾는다(쉼표로 이은 목록 포함), 상품 이름은 그대로")
    void patternWithNestedPieces() {
        String ko = "재고가 부족해 주문하지 못했습니다: 티셔츠(M) 남은 수량 1개, 바지(L) 품절. 장바구니에서 수량을 조정해 주세요.";
        assertThat(ClientText.translate(C, ko, 0))
                .isEqualTo("在庫不足のため注文できませんでした：티셔츠（M）残り1点、바지（L）売り切れ。カートで数量を調整してください。");
    }

    @Test
    @DisplayName("🔴 상품 이름에 \", \" 가 있어도 목록이 어긋나지 않는다 — 맞는 모양이 나올 때까지 이웃 조각을 잇는다")
    void listWithCommaInsideName() {
        String ko = "재고가 부족해 주문하지 못했습니다: 셔츠, 블루(M) 품절, 바지(L) 남은 수량 1개. 장바구니에서 수량을 조정해 주세요.";
        assertThat(ClientText.translate(C, ko, 0))
                .isEqualTo("在庫不足のため注文できませんでした：셔츠, 블루（M）売り切れ、바지（L）残り1点。カートで数量を調整してください。");
    }

    @Test
    @DisplayName("번역에서 순서를 바꿀 수 있다 — {1}·{0}")
    void reorder() {
        assertThat(ClientText.translate(C, "주문 42의 배송 정보을 찾을 수 없습니다.", 0)).isEqualTo("배송 정보（注文42）が見つかりません。");
    }

    @Test
    @DisplayName("카탈로그에 없으면 원문 그대로, '-' 는 운영자용이라 번역하지 않는다")
    void unknownAndInternal() {
        assertThat(ClientText.translate(C, "처음 보는 문구", 0)).isEqualTo("처음 보는 문구");
        assertThat(ClientText.translate(C, "payment.env 는 sandbox 또는 live 여야 합니다: x", 0))
                .isEqualTo("payment.env 는 sandbox 또는 live 여야 합니다: x");
    }

    @Test
    @DisplayName("한국어 요청·한글이 없는 문구·요청 밖에서는 아무것도 하지 않는다")
    void onlyJapaneseRequests() {
        assertThat(ClientText.localize("장바구니가 비어 있습니다.", Locale.KOREAN)).isEqualTo("장바구니가 비어 있습니다.");
        assertThat(ClientText.localize("Not found", Locale.JAPANESE)).isEqualTo("Not found");
        assertThat(ClientText.localize("장바구니가 비어 있습니다.")).as("요청 밖").isEqualTo("장바구니가 비어 있습니다.");
    }

    @Test
    @DisplayName("응답 봉투만 바꾼다 — 맵의 message·error, MessageResponse. 채팅 메시지 같은 DTO 는 건드리지 않는다")
    void adviceTouchesEnvelopesOnly() {
        org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.JAPANESE);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));
        ClientText.reset();
        try {
            // 실제 카탈로그(common 의 client-text.ja.txt)의 문구로
            String ko = "요청 값이 올바르지 않습니다.";
            var map = (java.util.Map<?, ?>) ClientTextResponseAdvice.localizeBody(java.util.Map.of("message", ko, "id", 1));
            assertThat(map.get("message")).isNotEqualTo(ko);
            assertThat(map.get("id")).isEqualTo(1);

            var envelope = new com.project.common.response.MessageResponse(ko);
            ClientTextResponseAdvice.localizeBody(envelope);
            assertThat(envelope.getMessage()).isNotEqualTo(ko);

            class ChatMessage { public String message = ko; public String getMessage() { return message; } public void setMessage(String m) { message = m; } }
            ChatMessage chat = new ChatMessage();
            ClientTextResponseAdvice.localizeBody(chat);
            assertThat(chat.message).isEqualTo(ko);
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
            ClientText.reset();
        }
    }
}
