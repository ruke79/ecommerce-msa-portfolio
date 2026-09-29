package com.project.userservice.security.totp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 2FA(TOTP) 비밀 암호화(docs/70). TOTP 는 검증할 때마다 원문이 필요해 해시가 아니라 되돌릴 수 있는 암호화다.
 */
@DisplayName("2FA 비밀 — 저장 암호화")
class TotpSecretCipherTest {

    private static final String JWT = "user-test-secret-user-test-secret-0123456";
    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";   // 32바이트 테스트 키
    private static final String SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

    private final TotpSecretCipher derivedOnly = new TotpSecretCipher("", JWT);
    private final TotpSecretCipher withKey = new TotpSecretCipher(KEY, JWT);

    @Test
    @DisplayName("🔴 저장 값에 비밀 원문이 없고, 풀면 원래 값")
    void roundTripHidesThePlaintext() {
        String sealed = withKey.encrypt(SECRET);

        assertThat(sealed).startsWith("enc:k1:").doesNotContain(SECRET);
        assertThat(withKey.decrypt(sealed)).isEqualTo(SECRET);
        assertThat(withKey.encrypt(SECRET)).as("매번 다른 IV").isNotEqualTo(sealed);
    }

    @Test
    @DisplayName("전용 키가 없으면 JWT 비밀에서 파생한 키로 — 표시가 다르다(enc:j1:)")
    void derivedKeyIsMarked() {
        String sealed = derivedOnly.encrypt(SECRET);

        assertThat(sealed).startsWith("enc:j1:");
        assertThat(derivedOnly.decrypt(sealed)).isEqualTo(SECRET);
    }

    @Test
    @DisplayName("🔴 전용 키를 넣은 뒤에도 파생 키 암호문을 읽는다 — 키를 넣는 배포에서 아무도 잠기지 않는다")
    void addingTheDedicatedKeyKeepsOldValuesReadable() {
        String old = derivedOnly.encrypt(SECRET);

        assertThat(withKey.decrypt(old)).isEqualTo(SECRET);
        assertThat(withKey.isCurrent(old)).as("백필이 전용 키로 다시 잠근다").isFalse();
    }

    @Test
    @DisplayName("이 변경 이전의 평문은 그대로 읽는다 — 배포 순간 2FA 가 끊기지 않게(백필이 암호화한다)")
    void legacyPlaintextIsReadable() {
        assertThat(withKey.decrypt(SECRET)).isEqualTo(SECRET);
        assertThat(withKey.isCurrent(SECRET)).isFalse();
    }

    @Test
    @DisplayName("다른 JWT 비밀 · 변조 · 키 없는 전용 암호문은 조용히 틀리지 않고 크게 실패한다")
    void failuresAreLoud() {
        String derived = derivedOnly.encrypt(SECRET);
        String dedicated = withKey.encrypt(SECRET);
        String tampered = dedicated.substring(0, dedicated.length() - 3) + (dedicated.endsWith("AAA") ? "BBB" : "AAA");

        assertThatThrownBy(() -> new TotpSecretCipher("", "another-jwt-secret-another-jwt-secret-01").decrypt(derived))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> withKey.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> derivedOnly.decrypt(dedicated)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("키 형식이 틀리면 기동에서 실패한다 — base64 32바이트")
    void badKeyFailsFast() {
        assertThatThrownBy(() -> new TotpSecretCipher("not-base64!!", JWT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TotpSecretCipher("c2hvcnQ=", JWT)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("🔴 변환기 — 쓸 때 암호화, 읽을 때 복호화. 엔티티 코드는 평문만 본다")
    void converterEncryptsOnWrite() {
        TotpSecretConverter converter = new TotpSecretConverter();
        TotpSecretConverter.use(withKey);

        String column = converter.convertToDatabaseColumn(SECRET);

        assertThat(column).startsWith("enc:k1:").doesNotContain(SECRET);
        assertThat(converter.convertToEntityAttribute(column)).isEqualTo(SECRET);
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }
}
