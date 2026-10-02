package com.project.userservice.security.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.userservice.security.risk.LoginRiskGuard.Signal;

/** 결정 규칙 — 아는 기기면 통과, 처음 보는 기기는 공격 신호가 있을 때만 메일 확인(docs/68 §19). 막지는 않는다. */
@DisplayName("위험 기반 로그인 — 결정 규칙")
class LoginRiskDecisionTest {

    @Test
    @DisplayName("🔴 아는 기기는 신호가 다 켜져도 통과 — 목록으로 들어온 공격자는 기기 쿠키를 갖고 있지 않다")
    void knownDevicePasses() {
        assertThat(LoginRiskGuard.needsChallenge(true, List.of(Signal.IP_FANOUT, Signal.TARGETED_ACCOUNT, Signal.GLOBAL_SURGE)))
                .isFalse();
    }

    @Test
    @DisplayName("처음 보는 기기 — 신호가 없으면 통과(새 기기 알림만), 하나라도 있으면 확인")
    void newDevice() {
        assertThat(LoginRiskGuard.needsChallenge(false, List.of())).isFalse();
        for (Signal s : Signal.values()) {
            assertThat(LoginRiskGuard.needsChallenge(false, List.of(s))).as(s.name()).isTrue();
        }
    }
}
