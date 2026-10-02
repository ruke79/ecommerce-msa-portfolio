package com.project.userservice.service;

import com.warrenstrange.googleauth.GoogleAuthenticatorKey;

import java.util.OptionalLong;

public interface TotpService {
    GoogleAuthenticatorKey generateSecret();

    String getQrCodeUrl(GoogleAuthenticatorKey secret, String username);

    boolean verifyCode(String secret, int code);

    /**
     * 코드가 맞는 시간 구간 번호(30초 단위) — 시계 오차로 앞뒤 한 구간까지 본다. 맞지 않으면 비어 있다.
     * 재사용 판정(TotpReplayGuard)은 이 번호로 한다(docs/68 §14).
     */
    // [JA] コードが合う時間区間番号(30 秒単位)— 時刻ずれで前後 1 区間まで見る。合わなければ空。再利用判定はこの番号で行う。
    OptionalLong matchedStep(String secret, int code, long nowMillis);
}
