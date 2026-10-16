package com.project.catalog_service.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 배너 · 기획전 링크 — http(s) 주소와 사이트 안 경로만. {@code javascript:} 는 저장형 XSS 였다.
 *
 * <p>[JA] バナー · 企画展リンク — http(s) とサイト内パスのみ。
 */
@DisplayName("SafeLinks — 화면이 href 로 그리는 링크")
class SafeLinksTest {

    @ParameterizedTest
    @ValueSource(strings = {"/events/summer", "/product/abc?x=1", "https://www.ysyproject.com/a", "HTTP://example.com"})
    @DisplayName("허용 — 사이트 안 경로 · http(s)")
    void allowed(String link) {
        assertThat(SafeLinks.check("  " + link + " ")).isEqualTo(link);
    }

    @Test
    @DisplayName("비었으면 링크 없음(null)")
    void blankIsNone() {
        assertThat(SafeLinks.check(null)).isNull();
        assertThat(SafeLinks.check("   ")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "JavaScript:alert(1)", "java\tscript:alert(1)", "java\nscript:alert(1)",
            "data:text/html,<script>alert(1)</script>", "vbscript:x", "//evil.example", "/\\evil.example", "https://",
            "events/summer", "https://a b.com", "ftp://x"})
    @DisplayName("🔴 거절 — 스크립트 스킴 · 제어 문자로 쪼갠 스킴 · 프로토콜 상대 주소 · 상대 경로")
    void rejected(String link) {
        assertThatThrownBy(() -> SafeLinks.check(link)).isInstanceOf(IllegalArgumentException.class);
    }
}
