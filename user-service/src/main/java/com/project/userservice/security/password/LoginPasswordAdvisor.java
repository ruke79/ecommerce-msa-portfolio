package com.project.userservice.security.password;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 로그인에 성공한 비밀번호가 유출 목록 · 흔한 목록에 있으면 "바꿔 주세요" 를 권한다 — 막지 않는다(docs/57 §22 · docs/68 §18).
 *
 * <p>🐛 예전에는 가입 · 변경 · 재설정에서만 봤다. 그 전에 정한 비밀번호가 그 뒤 다른 곳에서 새어도 이 계정 주인은 알 길이 없었다 —
 * 크리덴셜 스터핑은 바로 그 비밀번호로 들어온다.
 *
 * <p><b>로그인을 막지 않는다</b> — 막으면 그 사람은 비밀번호 재설정 메일 말고는 들어올 길이 없고, 외부 API 장애가 로그인 장애가 된다.
 * 응답의 {@code passwordAdvice} 로 화면이 안내만 한다. 매 로그인마다 외부 API 를 부르지 않도록 "괜찮음" 은 기억한다
 * ({@code auth:pw-ok:<userId>}, 기본 7일) — 비밀번호를 바꾸면 새 값은 이미 변경 경로에서 검사를 통과했으므로 그 기억이 틀리지 않는다.
 * 확인하지 못함(API 장애)은 1시간만 기억한다 — 장애 동안 로그인마다 상한(2초)을 기다리지 않게. Redis 에는 비밀번호 · 해시를 두지 않는다.
 *
 * <p>[JA] ログインに成功したパスワードが漏えい一覧 · ありふれた一覧にあれば「変えてください」と勧める — 止めない(docs/57 §22 · docs/68 §18)。
 * 🐛 以前は登録 · 変更 · 再設定でだけ見ていた。その前に決めたパスワードがその後よそで漏れても、このアカウントの持ち主には知るすべが
 * なかった — クレデンシャルスタッフィングはまさにそのパスワードで入ってくる。
 * <b>ログインを止めない</b> — 止めるとその人はパスワード再設定メール以外に入る道がなく、外部 API の障害がログイン障害になる。
 * 応答の {@code passwordAdvice} で画面が案内だけをする。ログインのたびに外部 API を呼ばないよう「問題なし」は覚えておく
 * ({@code auth:pw-ok:<userId>}、既定 7 日)— パスワードを変えれば新しい値はすでに変更経路で検査を通っているので、その記憶は間違わない。
 * 確認できなかった場合(API 障害)は 1 時間だけ覚える — 障害の間ログインごとに上限(2 秒)を待たないように。Redis にはパスワード ·
 * ハッシュを置かない。
 */
@Slf4j
@Component
public class LoginPasswordAdvisor {

    public static final String ADVICE_BREACHED = "breached";
    static final String KEY_PREFIX = "auth:pw-ok:";
    static final String ADVISED_METRIC = "auth.breached_password.login_advised";

    private final BreachedPasswordChecker checker;
    private final StringRedisTemplate redis;
    private final MeterRegistry meters;
    private final boolean enabled;
    private final Duration okTtl;

    public LoginPasswordAdvisor(BreachedPasswordChecker checker, StringRedisTemplate redis, MeterRegistry meters,
            @Value("${auth.breached-password.login-check.enabled:true}") boolean enabled,
            @Value("${auth.breached-password.login-check.ok-ttl:P7D}") Duration okTtl) {
        this.checker = checker;
        this.redis = redis;
        this.meters = meters;
        this.enabled = enabled;
        this.okTtl = okTtl;
    }

    /** @return 권할 것이 있으면 {@link #ADVICE_BREACHED}, 없으면 null. 어떤 실패도 로그인을 바꾸지 않는다. */
    // [JA] @return 勧めることがあれば {@link #ADVICE_BREACHED}、なければ null。どんな失敗もログインを変えない。
    public String adviceFor(Long userId, String rawPassword) {
        if (!enabled || userId == null || rawPassword == null || rawPassword.isEmpty()) {
            return null;
        }
        String key = KEY_PREFIX + userId;
        try {
            if (Boolean.TRUE.equals(redis.hasKey(key))) {
                return null;
            }
        } catch (RuntimeException e) {
            log.debug("비밀번호 권고 기억을 읽지 못했다 — 확인은 계속한다: {}", e.getClass().getSimpleName());
        }
        BreachedPasswordChecker.Verdict verdict = checker.check(rawPassword);
        if (verdict == BreachedPasswordChecker.Verdict.BREACHED || verdict == BreachedPasswordChecker.Verdict.COMMON) {
            meters.counter(ADVISED_METRIC, "reason", verdict.name().toLowerCase(java.util.Locale.ROOT)).increment();
            log.info("로그인 비밀번호가 유출 · 흔한 목록에 있다 — 변경을 권한다. userId={}", userId);
            return ADVICE_BREACHED;   // 기억하지 않는다 — 바꿀 때까지 로그인마다 권한다 ／ [JA] 記憶しない — 変えるまでログインのたびに勧める
        }
        try {
            redis.opsForValue().set(key, "1",
                    verdict == BreachedPasswordChecker.Verdict.UNKNOWN ? Duration.ofHours(1) : okTtl);
        } catch (RuntimeException e) {
            log.debug("비밀번호 권고 기억을 쓰지 못했다: {}", e.getClass().getSimpleName());
        }
        return null;
    }
}
