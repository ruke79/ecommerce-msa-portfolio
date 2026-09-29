package com.project.notification_service.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 메일 주소 검증 — <b>거부는 보수적으로, 마스킹은 확실하게</b>.
 *
 * <p>여기서 잘못 거부하면 정당한 사용자가 메일을 못 받고, 잘못 통과시키면 하드 바운스가 쌓여
 * 발신 도메인 평판이 떨어진다. 둘 중 <b>잘못 거부하는 쪽이 더 나쁘다</b> — 사용자가 가입을
 * 완료할 수 없게 되고, 그건 우리 쪽에서는 보이지도 않기 때문이다.
 */
@DisplayName("메일 주소")
class EmailAddressesTest {

    @Test
    @DisplayName("평범한 주소를 통과시킨다")
    void 정상_주소() {
        assertThat(EmailAddresses.isValid("alice@example.com")).isTrue();
        assertThat(EmailAddresses.isValid("alice.bob@example.co.kr")).isTrue();
        assertThat(EmailAddresses.isValid("alice+tag@example.com")).isTrue();
        assertThat(EmailAddresses.isValid("a_b-c@sub.example.co.jp")).isTrue();
    }

    @Test
    @DisplayName("플러스 태그를 거부하지 않는다 — Gmail 사용자가 흔히 쓴다")
    void 플러스_태그() {
        // 정규식을 좁게 짜면 여기서 걸린다. 실제로 흔한 주소이고, 이걸 막으면
        // "가입은 됐는데 메일이 안 온다"가 된다.
        assertThat(EmailAddresses.isValid("user+shop@gmail.com")).isTrue();
    }

    @Test
    @DisplayName("새 TLD 를 거부하지 않는다 — TLD 목록은 반드시 낡는다")
    void 새로운_tld() {
        assertThat(EmailAddresses.isValid("hello@example.shop")).isTrue();
        assertThat(EmailAddresses.isValid("hello@example.tokyo")).isTrue();
    }

    @Test
    @DisplayName("명백히 틀린 값은 막는다")
    void 잘못된_주소() {
        assertThat(EmailAddresses.isValid(null)).isFalse();
        assertThat(EmailAddresses.isValid("")).isFalse();
        assertThat(EmailAddresses.isValid("   ")).isFalse();
        assertThat(EmailAddresses.isValid("alice")).isFalse();          // @ 없음
        assertThat(EmailAddresses.isValid("alice@")).isFalse();         // 도메인 없음
        assertThat(EmailAddresses.isValid("@example.com")).isFalse();   // 로컬 없음
        assertThat(EmailAddresses.isValid("alice@example")).isFalse();  // 점 없음
        assertThat(EmailAddresses.isValid("alice bob@example.com")).isFalse(); // 공백
    }

    @Test
    @DisplayName("RFC 상한(320자)을 넘으면 막는다 — DB 컬럼 길이와 같아야 한다")
    void 길이_상한() {
        String tooLong = "a".repeat(320) + "@example.com";
        assertThat(EmailAddresses.isValid(tooLong)).isFalse();
        // 컬럼이 이 길이보다 짧으면 유효한 주소가 잘려 저장되고, 메일이 엉뚱한 곳으로 간다.
        assertThat(EmailAddresses.MAX_LENGTH).isEqualTo(320);
    }

    @Test
    @DisplayName("앞뒤 공백은 허용한다 — 사용자 입력에 흔하고, 자르면 유효하다")
    void 공백_다듬기() {
        assertThat(EmailAddresses.isValid("  alice@example.com  ")).isTrue();
    }

    @Test
    @DisplayName("마스킹은 도메인을 남긴다 — 특정 도메인으로만 실패하는 것이 가장 중요한 단서다")
    void 마스킹() {
        assertThat(EmailAddresses.mask("alice@example.com")).isEqualTo("a***@example.com");
        assertThat(EmailAddresses.mask("bob@naver.com")).isEqualTo("b***@naver.com");
    }

    @Test
    @DisplayName("마스킹은 로컬 파트를 남기지 않는다 — 로그 열람 권한이 연락처 열람 권한이 되면 안 된다")
    void 마스킹_로컬_숨김() {
        String masked = EmailAddresses.mask("verysecretname@example.com");
        assertThat(masked).doesNotContain("verysecretname");
        assertThat(masked).doesNotContain("erysecretname");
    }

    @Test
    @DisplayName("이상한 값을 마스킹해도 예외가 나지 않는다 — 로그를 찍다 죽으면 안 된다")
    void 마스킹_방어() {
        assertThat(EmailAddresses.mask(null)).isEqualTo("(none)");
        assertThat(EmailAddresses.mask("")).isEqualTo("(none)");
        assertThat(EmailAddresses.mask("@example.com")).isEqualTo("***");
        assertThat(EmailAddresses.mask("no-at-sign")).isEqualTo("***");
    }
}
