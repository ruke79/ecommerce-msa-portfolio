package com.project.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** JWT 서명 키 링 — 설정이 틀리면 뜨지 않고, kid 로 키를 고르며, 은퇴한 키는 거절한다(docs/68 §17). */
@DisplayName("JWT 키 링")
class JwtKeyRingTest {

    private static final String LEGACY = "legacy-secret-legacy-secret-legacy-01";
    private static final String K2 = "k2-secret-k2-secret-k2-secret-k2-0001";

    @Test
    @DisplayName("설정이 비어 있으면 예전과 같다 — legacy 하나로 서명 · 검증, kid 없는 옛 토큰도 legacy")
    void emptyConfigIsLegacyOnly() {
        JwtKeyRing r = JwtKeyRing.of(LEGACY, "", "", true);
        assertThat(r.activeKid()).isEqualTo("legacy");
        assertThat(new String(r.activeKey())).isEqualTo(LEGACY);
        assertThat(r.verificationKey(null)).get().extracting(String::new).isEqualTo(LEGACY);
        assertThat(r.verificationKey("legacy")).isPresent();
        assertThat(r.verificationKey("k2")).as("모르는 kid 는 거절").isEmpty();
    }

    @Test
    @DisplayName("🔴 추가 → 전환: 새 키로 서명하고 옛 토큰(legacy · kid 없음)도 계속 검증된다")
    void addThenActivate() {
        JwtKeyRing added = JwtKeyRing.of(LEGACY, "k2=" + K2, "", true);
        assertThat(added.activeKid()).as("추가만 했을 때는 서명이 그대로").isEqualTo("legacy");
        assertThat(added.verificationKey("k2")).as("검증자는 이미 새 키를 안다").isPresent();

        JwtKeyRing switched = JwtKeyRing.of(LEGACY, "k2=" + K2, "k2", true);
        assertThat(switched.activeKid()).isEqualTo("k2");
        assertThat(new String(switched.activeKey())).isEqualTo(K2);
        assertThat(switched.verificationKey(null)).as("전환 전에 발급된 토큰 — 로그아웃되지 않는다").isPresent();
    }

    @Test
    @DisplayName("🔴 legacy 은퇴 — kid 없는 토큰과 legacy 토큰을 거절한다")
    void retireLegacy() {
        JwtKeyRing r = JwtKeyRing.of(LEGACY, "k2=" + K2, "k2", false);
        assertThat(r.verificationKey(null)).isEmpty();
        assertThat(r.verificationKey("legacy")).isEmpty();
        assertThat(r.verificationKey("k2")).isPresent();
    }

    @Test
    @DisplayName("설정이 틀리면 기동을 멈춘다 — 없는 kid 로 전환 · legacy 로 서명하며 legacy 은퇴 · 짧은 키 · 중복 · 예약어 · 형식")
    void badConfigFailsFast() {
        assertThatThrownBy(() -> JwtKeyRing.of(LEGACY, "", "k2", true)).hasMessageContaining("먼저 추가");
        assertThatThrownBy(() -> JwtKeyRing.of(LEGACY, "k2=" + K2, "", false)).hasMessageContaining("legacy 키로 서명하면서");
        assertThatThrownBy(() -> JwtKeyRing.of(LEGACY, "k2=short", "", true))
                .hasMessageContaining("바이트입니다").hasMessageNotContaining("short");
        assertThatThrownBy(() -> JwtKeyRing.of(LEGACY, "k2=" + K2 + ",k2=" + K2, "", true)).hasMessageContaining("두 번");
        assertThatThrownBy(() -> JwtKeyRing.of(LEGACY, "legacy=" + K2, "", true)).hasMessageContaining("legacy");
        assertThatThrownBy(() -> JwtKeyRing.of(LEGACY, K2, "", true)).hasMessageContaining("형식");
        assertThatThrownBy(() -> JwtKeyRing.of("short", "", "", true)).hasMessageContaining("바이트입니다");
    }

    @Test
    @DisplayName("🔒 오류 문구와 kids() 에 비밀 값이 나오지 않는다")
    void neverLeaksSecrets() {
        JwtKeyRing r = JwtKeyRing.of(LEGACY, " k2 = " + K2 + " , k3=" + K2.replace('1', '3') + ",", "k3", true);
        assertThat(r.kids()).containsExactly("legacy", "k2", "k3");
        assertThat(r.kids().toString()).doesNotContain(K2).doesNotContain(LEGACY);
        assertThatThrownBy(() -> JwtKeyRing.of(LEGACY, "k2=" + K2, "nope", true))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(K2).doesNotContain(LEGACY));
    }
}
