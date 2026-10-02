package com.project.userservice.security.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 🔴 유출 비밀번호 검사 — 앞 5글자만 보내고(k-익명성), 유출 · 흔한 비밀번호는 거절, API 가 안 되면 막지 않는다(docs/68 §18).
 */
@DisplayName("유출 비밀번호 검사")
class BreachedPasswordCheckerTest {

    private static final String RAW = "Tr0ub4dor&3-horse";

    private final List<String> sent = new ArrayList<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private BreachedPasswordChecker checker(String response) {
        return new BreachedPasswordChecker(true, prefix -> { sent.add(prefix); return response; }, meters);
    }

    private static String suffix(String raw) {
        return BreachedPasswordChecker.sha1Hex(raw).substring(5);
    }

    @Test
    @DisplayName("🔴 유출 목록에 있으면 거절 — 응답의 개수 · 내용은 문구에 싣지 않는다")
    void breachedRejected() {
        BreachedPasswordChecker c = checker("0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n" + suffix(RAW) + ":42\r\n");
        assertThat(c.check(RAW)).isEqualTo(BreachedPasswordChecker.Verdict.BREACHED);
        assertThatThrownBy(() -> c.assertAcceptable(RAW)).isInstanceOf(BreachedPasswordException.class)
                .hasMessageNotContaining("42");
        assertThat(meters.counter(BreachedPasswordChecker.REJECTED_METRIC, "reason", "breached").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("🔒 밖으로 나가는 것은 SHA-1 의 앞 5글자뿐 — 비밀번호도 전체 해시도 보내지 않는다")
    void onlyPrefixLeaves() {
        checker("").check(RAW);
        assertThat(sent).containsExactly(BreachedPasswordChecker.sha1Hex(RAW).substring(0, 5));
        assertThat(sent.get(0)).hasSize(5).doesNotContain(RAW);
    }

    @Test
    @DisplayName("패딩 줄(개수 0)은 유출이 아니다 · 목록에 없으면 통과 · 대소문자 무관하게 맞춘다")
    void paddingAndMiss() {
        assertThat(checker(suffix(RAW) + ":0\n").check(RAW)).isEqualTo(BreachedPasswordChecker.Verdict.OK);
        assertThat(checker("0018A45C4D1DEF81644B54AB7F969B88D65:3\n").check(RAW)).isEqualTo(BreachedPasswordChecker.Verdict.OK);
        assertThat(checker(suffix(RAW).toLowerCase() + ":5\n").check(RAW)).isEqualTo(BreachedPasswordChecker.Verdict.BREACHED);
    }

    @Test
    @DisplayName("🔴 API 가 안 되면 막지 않는다(fail-open) — 지표를 남긴다")
    void unavailableFailsOpen() {
        BreachedPasswordChecker c = new BreachedPasswordChecker(true, prefix -> { throw new IOException("down"); }, meters);
        assertThat(c.check(RAW)).isEqualTo(BreachedPasswordChecker.Verdict.UNKNOWN);
        assertThatCode(() -> c.assertAcceptable(RAW)).doesNotThrowAnyException();
        assertThat(meters.counter(BreachedPasswordChecker.UNAVAILABLE_METRIC).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("🔴 흔한 비밀번호는 API 없이도 거절 — 꺼 놓아도, 대소문자를 바꿔도")
    void commonAlwaysRejected() {
        BreachedPasswordChecker off = new BreachedPasswordChecker(false, prefix -> { sent.add(prefix); return ""; }, meters);
        assertThat(off.check("QWER1234")).isEqualTo(BreachedPasswordChecker.Verdict.COMMON);
        assertThat(off.check("1q2w3e4r!")).isEqualTo(BreachedPasswordChecker.Verdict.COMMON);
        assertThat(off.check(RAW)).as("꺼 두면 API 를 부르지 않는다").isEqualTo(BreachedPasswordChecker.Verdict.OK);
        assertThat(sent).isEmpty();
    }
}
