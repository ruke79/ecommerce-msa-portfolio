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

    @Override
    public String getQrCodeUrl(GoogleAuthenticatorKey secret, String username){
        return GoogleAuthenticatorQRGenerator.getOtpAuthURL("Secure Notes Application", username, secret);
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
