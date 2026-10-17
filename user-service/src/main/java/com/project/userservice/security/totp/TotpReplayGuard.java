package com.project.userservice.security.totp;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * OTP(TOTP) 코드를 <b>한 번만</b> 쓰게 하고, 로그인한 상태의 2FA 변경 시도를 세어 막는다(docs/68 §14).
 *
 * <h3>재사용</h3>
 *
 * <p>🐛 예전: 검증이 앞뒤 한 구간(30초)씩 시계 오차를 허용하면서 <b>쓴 코드를 기록하지 않았다</b> — 같은 6자리를 약 90초 동안 몇 번이고
 * 쓸 수 있었다. 피싱 사이트가 사용자가 넣은 코드를 그대로 중계하거나, 어깨 너머로 본 코드를 같은 구간에 다시 쓰는 공격이 통했다.
 *
 * <p>지금: 사용자마다 마지막으로 받아들인 <b>시간 구간 번호</b>를 남기고, 같은 구간 또는 그 이전 구간의 코드는 거절한다(RFC 6238 §5.2 —
 * "검증자는 한 번 성공한 OTP 를 다시 받으면 안 된다"). 비교와 기록은 Lua 한 번으로 — 같은 코드로 동시에 두 요청이 와도 하나만 통과한다.
 * 기록은 허용 창이 지나면 의미가 없으므로 몇 분 뒤 지워진다.
 *
 * <h3>시도 제한</h3>
 *
 * <p>6자리를 무한히 대입하지 못하게 <b>사용자별로</b> 시도를 센다({@link #reserveAttempt} — 검증 전에) — 로그인 중의 2FA 설정 확인 · 끄기, 그리고 로그인 2단계(docs/68 §15)가
 * 같은 칸을 쓴다. 로그인 2단계의 대기 토큰도 토큰당 5번을 세지만(PendingTwoFactor), 그것만으로는 비밀번호로 다시 로그인해 새 토큰을 받으면
 * 다시 5번이었다 — 비밀번호를 아는 공격자(2FA 가 막아야 할 바로 그 상대)에게는 제한이 없었다.
 */
// [JA] OTP(TOTP)のコードを一度だけ使わせ、ログイン中の 2FA 変更の試行を数えて止める。以前は前後 1 区間の時刻ずれを許しつつ使ったコードを
//      記録せず、同じ 6 桁を約 90 秒何度でも使えた(フィッシングの中継・肩越しの再利用)。今はユーザーごとに最後に受け入れた時間区間番号を残し、
//      同じか以前の区間のコードを拒否する(RFC 6238 §5.2)。比較と記録は Lua 一回で原子的に。
@Component
public class TotpReplayGuard {

    static final String STEP_KEY = "totp:last-step:";
    static final String FAIL_KEY = "totp:mgmt-fail:";

    /** 마지막 구간보다 새 구간일 때만 기록하고 1, 아니면 0. */
    private static final DefaultRedisScript<Long> CLAIM = new DefaultRedisScript<>("""
            local cur = tonumber(redis.call('GET', KEYS[1]) or '-1')
            local step = tonumber(ARGV[1])
            if step <= cur then
              return 0
            end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            return 1
            """, Long.class);

    /** 실패를 하나 더하고, 처음이면 창의 길이만큼 TTL. 지금까지의 실패 수. */
    private static final DefaultRedisScript<Long> FAIL = new DefaultRedisScript<>("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return n
            """, Long.class);

    private final StringRedisTemplate redis;
    private final int maxFailures;
    private final long failureWindowMs;

    public TotpReplayGuard(StringRedisTemplate redis,
            @Value("${auth.totp.max-failures:5}") int maxFailures,
            @Value("${auth.totp.failure-window-ms:900000}") long failureWindowMs) {
        this.redis = redis;
        this.maxFailures = maxFailures;
        this.failureWindowMs = failureWindowMs;
    }

    /**
     * 이 구간의 코드를 받아들여도 되는가 — 되면 기록한다.
     *
     * @return false 면 이미 같은(또는 더 늦은) 구간의 코드가 쓰였다 = 재사용
     */
    public boolean claim(Long userId, long timeStep) {
        // 기록은 허용 창(앞뒤 한 구간 = 90초)보다 넉넉히 — 그 뒤에는 옛 구간의 코드가 어차피 맞지 않는다.
        Long r = redis.execute(CLAIM, List.of(STEP_KEY + userId), String.valueOf(timeStep), "300000");
        return r != null && r == 1L;
    }

    /**
     * 시도를 하나 <b>먼저</b> 센다 — 검증 전에 부른다. 돌려준 수가 {@link #maxFailures()} 를 넘으면 검증하지 않는다. 맞으면
     * {@link #clearFailures} 로 지운다. 읽고-검증하고-더하는 순서로는 동시에 보낸 코드가 모두 "잠기지 않음" 을 읽는다.
     *
     * <p>이 창에서 몇 번째 시도인지를 돌려준다 — {@link #maxFailures()} 와 <b>같아지는</b> 순간이 "막힌 순간" 이다(알림은 그때 한 번).
     *
     * <p>🐛 잠겼는지 <b>읽기만</b> 하는 {@code locked()} 도 있었다. 지금 그 길을 쓰는 곳은 없지만, 남겨 두면 "먼저 확인하고
     * 검증하자" 로 되돌아가기 쉽다 — 그것이 고친 결함이다(동시에 보낸 코드 수백 개가 모두 "잠기지 않음" 을 읽었다). 그래서 지웠다.
     */
    // [JA] 試行を先に数える — 検証の前に呼ぶ。上限を超えたら検証しない。読むだけの locked() は、確認してから検証する順序に戻りやすいので消した。
    public long reserveAttempt(Long userId) {
        Long n = redis.execute(FAIL, List.of(FAIL_KEY + userId), String.valueOf(failureWindowMs));
        return n == null ? 0L : n;
    }

    public int maxFailures() {
        return maxFailures;
    }

    public void clearFailures(Long userId) {
        redis.delete(FAIL_KEY + userId);
    }
}
