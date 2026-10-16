package com.project.common.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.Locale;
import java.util.StringJoiner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 일본어 번역의 처리 시간 상한 — 쉼표로 이은 긴 목록이 응답 문구로 나가도 요청 스레드를 붙잡지 않는다.
 *
 * <p>🐛 쉼표 목록은 "맞는 모양이 나올 때까지 이웃 조각을 이어 붙여 다시 번역" 을 깊이 3 까지 되풀이해, 카탈로그에 없는 조각이
 * 많으면 시간이 조각 수에 대해 다항식으로 늘었다(40 조각에 수 초). 입력을 이어 붙인 오류 문구 · 알림 본문이 이 경로를 탄다.
 *
 * <p>[JA] 日本語翻訳の処理時間の上限 — カンマでつないだ長いリストが応答文言に出てもリクエストスレッドを捕まえない。
 */
@DisplayName("ClientText — 번역 처리 시간 상한")
class ClientTextBoundsTest {

    private static String unknownList(int pieces) {
        StringJoiner j = new StringJoiner(", ");
        for (int i = 0; i < pieces; i++) {
            j.add("알수없는조각" + i + "(" + i + ")");
        }
        return j.toString();
    }

    @Test
    @DisplayName("🔴 카탈로그에 없는 조각 120 개 — 빨리 끝나고(2초 안) 원문 그대로")
    void longUnknownListIsBounded() {
        String text = unknownList(120);
        String out = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> ClientText.localize(text, Locale.JAPANESE), "번역이 조각 수에 대해 폭증한다");
        assertThat(out).isEqualTo(text);
    }

    @Test
    @DisplayName("🔴 패턴의 빈칸(깊이 1)에 들어간 긴 목록도 — 빨리 끝난다")
    void longListInsidePatternIsBounded() {
        String text = "형식이 틀린 항목입니다: " + unknownList(160);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> ClientText.localize(text, Locale.JAPANESE),
                "번역이 조각 수에 대해 폭증한다");
    }

    /** 🐛 예전: 조각이 상한(24)을 넘으면 목록 전체가 원문이었다 — 한 조각도 번역되지 않았다. */
    @Test
    @DisplayName("🔴 상한을 넘는 목록도 앞의 상한만큼은 번역하고 나머지만 원문")
    void longKnownListTranslatesItsHead() {
        StringJoiner j = new StringJoiner(", ");
        for (int i = 0; i < ClientText.MAX_LIST_PIECES + 6; i++) {
            j.add("로그인이 필요합니다.");
        }
        String out = ClientText.localize(j.toString(), Locale.JAPANESE);
        assertThat(out).startsWith("ログインが必要です。");
        assertThat(out.split("ログインが必要です。", -1).length - 1).isEqualTo(ClientText.MAX_LIST_PIECES);
        assertThat(out).endsWith("로그인이 필요합니다.");
    }
}
