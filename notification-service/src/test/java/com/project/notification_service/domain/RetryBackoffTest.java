package com.project.notification_service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 재시도 간격.
 *
 * <p>🐛 이 규칙이 없어서 메일 5회 재시도가 <b>20초 만에</b> 소진됐다. 상한("몇 번")만 있고
 * 간격("언제")이 없으면 재시도는 이름뿐이다 — 같은 순간에 다섯 번 때리는 것은 한 번 때리는
 * 것과 결과가 같다.
 */
@DisplayName("재시도 간격")
class RetryBackoffTest {

    @Test
    @DisplayName("첫 실패는 기본 간격만큼 기다린다")
    void 첫_실패() {
        assertThat(RetryBackoff.delayMillis(1, 60_000, 1_800_000)).isEqualTo(60_000);
    }

    @Test
    @DisplayName("시도할 때마다 두 배가 된다")
    void 배증() {
        assertThat(RetryBackoff.delayMillis(2, 60_000, 1_800_000)).isEqualTo(120_000);
        assertThat(RetryBackoff.delayMillis(3, 60_000, 1_800_000)).isEqualTo(240_000);
        assertThat(RetryBackoff.delayMillis(4, 60_000, 1_800_000)).isEqualTo(480_000);
    }

    /**
     * 🔴 이 테스트가 이 파일의 핵심이다. 운영 기본값(1분/5회)으로 마지막 시도가 <b>몇 분 뒤</b>
     * 여야 그레이리스팅이 풀리는 구간을 지난다. 20초로 돌아가면 여기서 잡힌다.
     */
    @Test
    @DisplayName("🔴 기본 설정에서 재시도 창이 10분을 넘는다 — 20초로 돌아가면 안 된다")
    void 재시도_창() {
        long total = 0;
        for (int attempts = 1; attempts <= 4; attempts++) {   // 5회째가 마지막 시도
            total += RetryBackoff.delayMillis(attempts, 60_000, 1_800_000);
        }
        assertThat(total).isGreaterThan(10 * 60_000L);
    }

    @Test
    @DisplayName("상한에서 멈춘다 — 장애가 길어도 하루에 몇 번은 시도한다")
    void 상한() {
        assertThat(RetryBackoff.delayMillis(20, 60_000, 1_800_000)).isEqualTo(1_800_000);
    }

    /**
     * 🐛 {@code base << attempts} 로 구현하면 attempts 가 크거나 base 가 클 때 부호가 뒤집혀
     * <b>음수</b>가 된다. 그러면 다음 시도 시각이 과거가 되어 백오프가 통째로 사라진다 —
     * 고치려던 버그가 조용히 되살아난다.
     */
    @Test
    @DisplayName("🐛 시도 횟수가 터무니없이 커도 음수가 되지 않는다")
    void 오버플로() {
        assertThat(RetryBackoff.delayMillis(Integer.MAX_VALUE, 60_000, 1_800_000))
                .isEqualTo(1_800_000);
        assertThat(RetryBackoff.delayMillis(1_000, Long.MAX_VALUE / 2, 1_800_000))
                .isPositive();
    }

    @Test
    @DisplayName("상한이 기본 간격보다 작으면 기본 간격을 쓴다 — 설정 실수로 0초가 되지 않는다")
    void 상한이_더_작을_때() {
        assertThat(RetryBackoff.delayMillis(3, 60_000, 1_000)).isEqualTo(60_000);
    }

    @Test
    @DisplayName("간격을 0으로 끄면 곧바로 재시도한다 — 예전 동작을 명시적으로 고를 수 있다")
    void 꺼기() {
        assertThat(RetryBackoff.delayMillis(3, 0, 1_800_000)).isZero();
    }

    @Test
    @DisplayName("다음 시각은 현재로부터 그만큼 뒤다")
    void 다음_시각() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 18, 4, 12, 10);

        assertThat(RetryBackoff.nextAttemptAt(now, 1, 60_000, 1_800_000))
                .isEqualTo(now.plusMinutes(1));
        assertThat(RetryBackoff.nextAttemptAt(now, 3, 60_000, 1_800_000))
                .isEqualTo(now.plusMinutes(4));
    }
}
