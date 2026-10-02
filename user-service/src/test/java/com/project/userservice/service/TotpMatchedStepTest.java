package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.userservice.service.impl.TotpServiceImpl;
import com.warrenstrange.googleauth.GoogleAuthenticator;

/** 코드가 맞는 시간 구간 — 재사용 판정의 기준(docs/68 §14). */
@DisplayName("TOTP — 코드가 맞는 시간 구간")
class TotpMatchedStepTest {

    private final GoogleAuthenticator gAuth = new GoogleAuthenticator();
    private final TotpServiceImpl totp = new TotpServiceImpl(gAuth);
    private final String secret = gAuth.createCredentials().getKey();
    private final long now = 1_790_000_000_000L;
    private final long step = now / 30_000;

    @Test
    @DisplayName("지금 구간 · 앞뒤 한 구간(시계 오차)의 코드는 그 구간 번호를 돌려준다")
    void withinWindow() {
        for (long s : new long[] { step, step - 1, step + 1 }) {
            int code = gAuth.getTotpPassword(secret, s * 30_000);
            assertThat(totp.matchedStep(secret, code, now)).hasValue(s);
        }
    }

    @Test
    @DisplayName("창 밖 · 틀린 코드 · 비밀 없음은 비어 있다")
    void outside() {
        assertThat(totp.matchedStep(secret, gAuth.getTotpPassword(secret, (step - 3) * 30_000), now)).isEmpty();
        assertThat(totp.matchedStep(null, 123456, now)).isEmpty();
    }
}
