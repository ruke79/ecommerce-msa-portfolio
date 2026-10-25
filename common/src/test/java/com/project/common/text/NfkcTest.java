package com.project.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NFKC — 찾기 · 식별용 값만 같은 글자로")
class NfkcTest {

    @Test
    @DisplayName("🔴 전각 영숫자 · 반각 카나 · 전각 기호는 표준 글자로 — 일본어 IME 가 내는 모양")
    void imeCompatibilityCharacters() {
        assertThat(Nfkc.of("ＴＳＨＩＲＴ")).isEqualTo("TSHIRT");
        assertThat(Nfkc.of("ｼｬﾂ")).isEqualTo("シャツ");
        assertThat(Nfkc.of("ｶﾞｲﾄﾞ")).as("반각 탁점은 앞 글자와 합쳐진다").isEqualTo("ガイド");
        assertThat(Nfkc.of("ｔｅｓｔ＠ｅｘａｍｐｌｅ．ｃｏｍ")).isEqualTo("test@example.com");
        assertThat(Nfkc.of("１２３－４５６７")).isEqualTo("123-4567");
        assertThat(Nfkc.of("０９０−１２３４−５６７８")).as("U+2212 빼기는 바뀌지 않는다 — 전화 형식은 libphonenumber 가 본다")
                .isEqualTo("090−1234−5678");
    }

    @Test
    @DisplayName("이미 표준인 글 · 한글 · 일본어 · 이모지는 그대로 — 앞뒤 공백(전각 공백 포함)만 지운다")
    void standardTextUnchanged() {
        assertThat(Nfkc.of("셔츠 シャツ 😀")).isEqualTo("셔츠 シャツ 😀");
        assertThat(Nfkc.of("　 user@example.com \t")).isEqualTo("user@example.com");
        assertThat(Nfkc.of(null)).isNull();
        assertThat(Nfkc.of("   ")).isEmpty();
    }

    @Test
    @DisplayName("검색어는 연속 공백(전각 공백 포함)을 하나로 — 대소문자는 DB 가 맞춘다")
    void searchKeyCollapsesSpaces() {
        assertThat(Nfkc.searchKey("  ＮＩＫＥ　　ｼｭｰｽﾞ ")).isEqualTo("NIKE シューズ");
        assertThat(Nfkc.searchKey(null)).isNull();
    }
}
