package com.project.userservice.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 동시 갱신 유예 값의 암호화(docs/68 §10).
 *
 * <p>리프레시 토큰은 Redis 에 해시로만 둔다. 유예만은 두 번째 탭에 <b>같은 토큰</b>을 돌려줘야 해서 원문을 꺼낼 수 있어야 한다 — 해시로는
 * 불가능하다. 원문 대신 서버 키로 암호화한 값을 둔다.
 */
@DisplayName("유예 토큰 암호화")
class GraceTokenCipherTest {

    private static final String SECRET = "user-test-secret-user-test-secret-0123456";
    private final GraceTokenCipher cipher = new GraceTokenCipher(SECRET);
    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhIn0.c2lnbmF0dXJlLXZhbHVl";

    @Test
    @DisplayName("같은 세션으로 풀면 원래 토큰 — 두 번째 탭이 받는 값")
    void roundTrip() {
        assertThat(cipher.open("sess-1", cipher.seal("sess-1", TOKEN))).isEqualTo(TOKEN);
    }

    @Test
    @DisplayName("🔴 저장되는 값에 원문이 없다 — 매번 다른 암호문(IV 가 무작위)")
    void sealedValueHidesTheToken() {
        String a = cipher.seal("sess-1", TOKEN);
        String b = cipher.seal("sess-1", TOKEN);

        assertThat(a).doesNotContain("c2lnbmF0dXJlLXZhbHVl").doesNotContain("eyJ").startsWith("v1:");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("다른 세션의 유예 키로 옮겨 붙이면 풀리지 않는다(AAD = 옛 세션 id)")
    void boundToItsSession() {
        assertThat(cipher.open("sess-2", cipher.seal("sess-1", TOKEN))).isNull();
    }

    @Test
    @DisplayName("한 글자라도 바뀌면 풀리지 않는다(GCM 인증 태그)")
    void tamperIsDetected() {
        String sealed = cipher.seal("sess-1", TOKEN);
        char last = sealed.charAt(sealed.length() - 2);
        String tampered = sealed.substring(0, sealed.length() - 2) + (last == 'A' ? 'B' : 'A') + sealed.charAt(sealed.length() - 1);

        assertThat(cipher.open("sess-1", tampered)).isNull();
    }

    @Test
    @DisplayName("🔴 Redis 덤프만으로는 못 푼다 — 키는 서버 비밀에서 파생되고 Redis 에 없다")
    void differentSecretCannotOpen() {
        String sealed = cipher.seal("sess-1", TOKEN);

        assertThat(new GraceTokenCipher("another-secret-another-secret-0123456789").open("sess-1", sealed)).isNull();
    }

    @Test
    @DisplayName("형식이 아니면 null — 예외로 갱신 요청을 죽이지 않는다")
    void garbageIsNull() {
        assertThat(cipher.open("sess-1", null)).isNull();
        assertThat(cipher.open("sess-1", "PENDING")).isNull();
        assertThat(cipher.open("sess-1", "v1:!!!")).isNull();
        assertThat(cipher.open("sess-1", "v1:AAAA")).isNull();
    }
}
