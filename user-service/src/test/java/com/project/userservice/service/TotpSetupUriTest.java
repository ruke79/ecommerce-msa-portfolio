package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.userservice.service.impl.TotpServiceImpl;
import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorKey;

/**
 * 2FA 등록 응답 — 비밀이 제3자 서버로 가지 않는다.
 *
 * <p>🐛 예전: {@code https://api.qrserver.com/v1/create-qr-code/?data=otpauth…secret=…} 를 돌려줬다. 그것을 {@code <img src>} 로 그리면
 * TOTP 비밀이 외부 QR 서버로 전송된다.
 *
 * <p>[JA] 2FA 登録応答 — 秘密が第三者のサーバーへ行かない。
 */
@DisplayName("2FA 등록 URI — otpauth:// 만, 외부 QR 서버 없음")
class TotpSetupUriTest {

    @Test
    @DisplayName("🔴 otpauth://totp/ 로 시작하고 http 주소가 없다 — 비밀은 이 URI 안에만 있다")
    void returnsOtpAuthUriOnly() {
        TotpServiceImpl totp = new TotpServiceImpl();
        GoogleAuthenticatorKey key = new GoogleAuthenticator().createCredentials();

        String uri = totp.getQrCodeUrl(key, "kim");

        assertThat(uri).startsWith("otpauth://totp/").doesNotContain("http://", "https://", "qrserver")
                .contains("secret=" + key.getKey()).contains("issuer=YSY");
        assertThat(uri).doesNotContain("Secure");
    }
}
