package com.project.userservice.service.impl;

import com.project.userservice.service.TotpService;
import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorKey;
import com.warrenstrange.googleauth.GoogleAuthenticatorQRGenerator;
import org.springframework.stereotype.Service;

@Service
public class TotpServiceImpl implements TotpService {

    private final GoogleAuthenticator gAuth;

    public TotpServiceImpl(GoogleAuthenticator gAuth) {
        this.gAuth = gAuth;
    }

    public TotpServiceImpl() {
        this.gAuth = new GoogleAuthenticator();
    }

    @Override
    public GoogleAuthenticatorKey generateSecret(){
        return gAuth.createCredentials();
    }

    /** 인증 앱에 보이는 발급자 이름. */
    static final String ISSUER = "YSY Shop";

    /**
     * 인증 앱 등록 URI({@code otpauth://totp/…}) — QR 은 받는 쪽(브라우저)이 그린다.
     *
     * <p>🔒 🐛 예전: {@code getOtpAuthURL} 을 썼다 — 이 함수는 {@code https://api.qrserver.com/v1/create-qr-code/?data=otpauth…secret=…}
     * 를 돌려준다. 화면이 그것을 {@code <img src>} 로 그리는 순간 <b>TOTP 비밀이 제3자 서버로</b> 간다(그 서버의 접근 로그에도 남는다).
     * 발급자 이름도 남의 예제의 "Secure Notes Application" 이었다.
     */
    // [JA] 認証アプリ登録 URI(otpauth://) — QR は受け側が描く。以前は第三者の QR サーバーの URL を返し、表示した瞬間に TOTP の秘密が外へ出た。
    @Override
    public String getQrCodeUrl(GoogleAuthenticatorKey secret, String username){
        return GoogleAuthenticatorQRGenerator.getOtpAuthTotpURL(ISSUER, username, secret);
    }

    @Override
    public boolean verifyCode(String secret, int code){
        return gAuth.authorize(secret, code);
    }

    /** 30초 구간 — Google Authenticator · RFC 6238 의 기본값. */
    static final long STEP_MS = 30_000L;

    @Override
    public java.util.OptionalLong matchedStep(String secret, int code, long nowMillis) {
        if (secret == null || secret.isBlank() || code < 0) {
            return java.util.OptionalLong.empty();
        }
        long now = nowMillis / STEP_MS;
        // 지금 구간을 먼저 — 앞뒤는 시계 오차 몫이다(authorize 의 기본 창과 같다).
        for (long step : new long[] { now, now - 1, now + 1 }) {
            if (gAuth.getTotpPassword(secret, step * STEP_MS) == code) {
                return java.util.OptionalLong.of(step);
            }
        }
        return java.util.OptionalLong.empty();
    }
}
