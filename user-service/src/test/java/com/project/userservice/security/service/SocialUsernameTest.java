package com.project.userservice.security.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🔴 소셜 가입의 username 은 users.username(varchar(20))에 들어간다.
 *
 * <p>🐛 예전: {@code google_} + 21자리 sub = 28자 → 구글 · 페이스북 · 네이버 · LINE 가입이 DB 에서 거절됐다.
 */
@DisplayName("소셜 username · 표시 이름 길이")
class SocialUsernameTest {

    @Test
    @DisplayName("🔴 구글(21자리 sub) · 네이버(긴 id) · LINE — 20자 이하, 같은 계정이면 늘 같은 값, 계정마다 다르다")
    void longIdsFitAndStayStable() {
        String g1 = CustomOAuth2UserService.socialUsername("google", "109876543210987654321");
        String g2 = CustomOAuth2UserService.socialUsername("google", "109876543210987654322");
        String naver = CustomOAuth2UserService.socialUsername("naver", "Abc123Def456Ghi789Jkl012Mno345Pqr678Stu9x");
        String line = CustomOAuth2UserService.socialUsername("line", "U4af4980629c5f8a9b2b7c0d1e2f3a4b5");

        assertThat(g1).hasSizeLessThanOrEqualTo(20).startsWith("google_");
        assertThat(naver).hasSizeLessThanOrEqualTo(20).startsWith("naver_");
        assertThat(line).hasSizeLessThanOrEqualTo(20).startsWith("line_");
        assertThat(CustomOAuth2UserService.socialUsername("google", "109876543210987654321")).isEqualTo(g1);
        assertThat(g1).isNotEqualTo(g2);
    }

    @Test
    @DisplayName("이미 맞는 것(깃허브 · 카카오 · 기존 계정)은 그대로 — provider_providerId")
    void shortIdsUnchanged() {
        assertThat(CustomOAuth2UserService.socialUsername("github", "583231")).isEqualTo("github_583231");
        assertThat(CustomOAuth2UserService.socialUsername("kakao", "3141592653")).isEqualTo("kakao_3141592653");
    }

    @Test
    @DisplayName("표시 이름은 20자로 자른다 — 이모지를 반으로 자르지 않는다 · null 은 그대로")
    void displayNameCapped() {
        assertThat(CustomOAuth2UserService.cap("가".repeat(25), 20)).hasSize(20);
        assertThat(CustomOAuth2UserService.cap("😀".repeat(25), 20)).isEqualTo("😀".repeat(20));
        assertThat(CustomOAuth2UserService.cap("Alice", 20)).isEqualTo("Alice");
        assertThat(CustomOAuth2UserService.cap(null, 20)).isNull();
    }
}
