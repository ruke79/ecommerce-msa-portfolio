package com.project.notification_service.domain;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 재시도 간격을 정한다 — <b>시도 횟수 상한만으로는 재시도가 되지 않는다.</b>
 *
 * <p>이것이 없어서 실제로 이런 일이 있었다. {@code MailDispatcher} 는 상한을 5회로 두고 그 이유를
 * 주석에 이렇게 적어 두었다:
 *
 * <blockquote>그레이리스팅을 쓰는 수신 서버는 처음 온 발신자를 일부러 한 번 거절하고 재시도를
 * 기다린다 — 상한이 작으면 그런 서버로는 첫 메일이 영영 안 간다.</blockquote>
 *
 * <p>그런데 디스패처는 5초마다 돌고, 선점 질의는 <b>상한에 닿지 않은 행을 무조건 다시 집었다.</b>
 * 그래서 다섯 번의 시도가 <b>20초 안에 전부 소진</b>됐다. 그레이리스팅은 보통 수 분을 기다려야
 * 풀리므로, 주석이 막겠다고 한 바로 그 실패가 그대로 일어났다. 운영 로그가 그 증거다:
 *
 * <pre>
 * 04:12:10 [MAIL:retryable] … 04:12:15 … 04:12:20 … 04:12:25 … 04:12:30 재시도 상한 도달, 포기
 * </pre>
 *
 * <p><b>상한은 "몇 번"이고 백오프는 "언제"다.</b> 둘 중 하나만 있으면 재시도는 무의미하다 —
 * 같은 순간에 다섯 번 때리는 것은 한 번 때리는 것과 결과가 같고, 실패 원인이 일시적일수록
 * 더 그렇다.
 *
 * <p>간격은 시도할 때마다 두 배가 되고 상한에서 멈춘다. 지터는 넣지 않았다 — 이 서비스의
 * 재시도는 행 단위로 흩어져 있어 동시에 깨어나는 무리가 생기지 않고, 지터가 없으면 "언제 다시
 * 시도되는가"를 {@code next_attempt_at} 만 보고 정확히 말할 수 있다.
 */
public final class RetryBackoff {

    private RetryBackoff() {
    }

    /**
     * 다음 시도 시각.
     *
     * @param attempts 방금 실패까지 포함한 <b>누적</b> 시도 횟수. 1이면 첫 실패다.
     */
    public static LocalDateTime nextAttemptAt(LocalDateTime now, int attempts,
                                              long baseMs, long maxMs) {
        return now.plus(delayMillis(attempts, baseMs, maxMs), ChronoUnit.MILLIS);
    }

    /**
     * 기다릴 시간(ms). {@code base × 2^(attempts-1)} 을 {@code max} 에서 자른다.
     *
     * <p>곱셈 대신 조건부 배증 반복을 쓴다. {@code base << attempts} 는 설정이 잘못돼 attempts 가
     * 크면 <b>부호가 뒤집혀 음수가 되고</b>, 그러면 다음 시도 시각이 과거가 되어 백오프가 통째로
     * 사라진다 — 고치려던 버그가 조용히 되살아난다. 여기서는 상한에 닿는 순간 멈추므로 그럴 수 없다.
     */
    public static long delayMillis(int attempts, long baseMs, long maxMs) {
        if (baseMs <= 0) {
            return 0;
        }
        long cap = maxMs <= 0 ? baseMs : Math.max(baseMs, maxMs);

        long delay = baseMs;
        for (int i = 1; i < attempts && delay < cap; i++) {
            delay <<= 1;
        }
        return Math.min(delay, cap);
    }
}
