package com.project.userservice.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.LockedException;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 로그인 실패 카운터와 잠금 — 비밀번호 대입 방어의 계정 축.
 *
 * <p>게이트웨이의 IP 레이트리밋은 이미 있다(분당 수십 회). 이건 다른 축이다: IP 를
 * 바꿔 가며 **한 계정**을 노리는 공격(크리덴셜 스터핑)은 IP 버킷으로 못 막는다.
 * 연속 {@code auth.lock.max-failures}(기본 5)회 실패 → {@code auth.lock.duration}
 * (기본 15분) 잠금, 만료는 자동 해제.
 *
 * <p>설계 결정:
 * <ul>
 *   <li><b>영구 잠금은 없다.</b> 만료 시각이 지나면 저절로 풀린다 — 영구 잠금은 "고객이 전화할 때까지 서비스 불능"과 같은 말이고,
 *       공격자에게는 남의 계정을 잠그는 DoS 버튼이 된다. 15분이면 대입 속도는 사실상 0이 된다</li>
 *   <li><b>시도는 비밀번호를 검사하기 전에 센다</b>({@link #reserveAttempt}, Redis Lua 한 번). 🐛 예전에는 DB 에서 "읽고 → 비교하고 →
 *       틀리면 더해 저장" 이었다 — 버전 없는 읽기-고치기-쓰기라 동시에 보낸 요청이 서로의 증가를 덮어, 병렬로 보내면 횟수가 거의 늘지
 *       않았다. 이제 한도를 넘은 시도는 비밀번호를 비교하지도 않는다. 맞으면 {@link #onSuccess} 가 지운다</li>
 *   <li><b>있는 계정과 없는 계정을 똑같이 센다.</b> 🐛 예전에는 없는 계정을 세지 않아(부채널을 막으려던 것), 같은 주소로 다섯 번 틀린
 *       뒤 여섯 번째에 "잠시 잠겼습니다" 가 나오면 그 계정이 <b>있다</b>는 뜻이 됐다. 열쇠는 이메일의 해시라 Redis 에 주소가 남지 않는다</li>
 * </ul>
 *
 * <p>[JA] ログイン失敗カウンターとロック — パスワード総当たり防御のアカウント軸。
 *
 * <p>[JA] ゲートウェイの IP レート制限はすでにある(毎分数十回)。これは別の軸だ: IP を変えながら**一つのアカウント**を狙う攻撃
 * (クレデンシャルスタッフィング)は IP バケットでは防げない。連続 {@code auth.lock.max-failures}(既定 5)回失敗 →
 * {@code auth.lock.duration}(既定 15 分)ロック、期限切れで自動解除。
 *
 * <p>[JA] 設計判断:
 * <ul>
 *   <li><b>永久ロックはない。</b> 期限を過ぎると自然に解ける — 永久ロックは「客が電話するまでサービス不能」と同じ意味で、
 *       攻撃者には他人のアカウントをロックする DoS ボタンになる。15 分なら総当たり速度は事実上 0 になる</li>
 *   <li><b>試行はパスワードを検査する前に数える</b>({@link #reserveAttempt}, Redis Lua 一回)。🐛 以前は DB で「読む → 比較 →
 *       間違えば加算して保存」だった — バージョンなしの読み取り-変更-書き込みなので、同時に送ったリクエストが互いの増分を上書きし、
 *       並列で送ると回数がほとんど増えなかった。今は上限を超えた試行はパスワードを比較すらしない。合えば {@link #onSuccess} が消す</li>
 *   <li><b>存在するアカウントもしないアカウントも同じく数える。</b> 🐛 以前は存在しないアカウントを数えず(サイドチャネルを防ぐつもりだった)、
 *       同じアドレスで五回間違えた後の六回目に「しばらくロックされました」が出ると、そのアカウントが<b>存在する</b>という意味になった。
 *       キーはメールのハッシュなので Redis にアドレスは残らない</li>
 * </ul>
 */
@Slf4j
@Service
public class LoginAttemptService {

    static final String ATTEMPTS_KEY = "login:attempts:";
    static final String LOCK_KEY = "login:lock:";

    /**
     * 잠겨 있으면 남은 시간(음수 ms), 아니면 이번 시도를 더한 수. 한도를 넘는 순간 잠그고 카운터를 지운다(풀린 뒤에는 처음부터).
     * KEYS[1]=잠금 KEYS[2]=시도 · ARGV[1]=잠금(=창) 길이 ms ARGV[2]=한도
     *
     * <p>[JA] ロック中なら残り時間(負の ms)、そうでなければ今回の試行を足した数。上限を超えた瞬間にロックしてカウンターを消す
     * (解除後は最初から)。KEYS[1]=ロック KEYS[2]=試行 · ARGV[1]=ロック(=ウィンドウ)の長さ ms ARGV[2]=上限
     */
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl > 0 then
              return -ttl
            end
            local n = redis.call('INCR', KEYS[2])
            if n == 1 then
              redis.call('PEXPIRE', KEYS[2], ARGV[1])
            end
            if n > tonumber(ARGV[2]) then
              redis.call('SET', KEYS[1], '1', 'PX', ARGV[1])
              redis.call('DEL', KEYS[2])
              return -tonumber(ARGV[1])
            end
            return n
            """, Long.class);

    private final StringRedisTemplate redis;
    private final int maxFailures;
    private final Duration lockDuration;

    public LoginAttemptService(StringRedisTemplate redis,
            @Value("${auth.lock.max-failures:5}") int maxFailures,
            @Value("${auth.lock.duration:PT15M}") Duration lockDuration) {
        this.redis = redis;
        this.maxFailures = maxFailures;
        this.lockDuration = lockDuration;
    }

    /**
     * 비밀번호를 검사하기 **전에** 부른다 — 이번 시도를 센다. 잠겨 있거나 이번 시도로 한도를 넘으면 {@link LockedException}
     * (잠긴 동안에는 맞는 비밀번호도 통과시키지 않는다 — 통과시키면 공격자가 잠금을 무시하고 계속 시도할 수 있다).
     *
     * <p>[JA] パスワードを検査する**前に**呼ぶ — 今回の試行を数える。ロック中か今回の試行で上限を超えたら {@link LockedException}
     * (ロック中は正しいパスワードも通さない — 通すと攻撃者がロックを無視して試し続けられる)。
     */
    public void reserveAttempt(String email) {
        String id = idOf(email);
        Long r;
        try {
            r = redis.execute(RESERVE, List.of(LOCK_KEY + id, ATTEMPTS_KEY + id),
                    String.valueOf(lockDuration.toMillis()), String.valueOf(maxFailures));
        } catch (DataAccessException e) {
            // 셀 수 없으면 받지 않는다 — 막지 못한 채 대입을 허용하는 것보다 로그인을 잠시 못 하는 쪽(503).
            // [JA] 数えられなければ受け付けない — 防げないまま総当たりを許すより、ログインがしばらくできない方をとる(503)。
            throw new AuthenticationServiceException("login attempt store unavailable", e);
        }
        if (r != null && r < 0) {
            long minutes = Math.max(1, Duration.ofMillis(-r).toMinutes());
            // 값(비밀번호)은 물론이고 몇 번째 시도인지도 응답에는 싣지 않는다 — 임계값을 알려주면 공격자는 그 직전까지만 시도한다.
            // [JA] 値(パスワード)はもちろん、何回目の試行かも応答に載せない — 閾値を教えると攻撃者はその直前までだけ試す。
            throw new LockedException("로그인이 잠시 잠겼습니다. 약 " + minutes + "분 후 다시 시도해 주세요.");
        }
    }

    /** 비밀번호가 틀렸다 — 시도는 이미 셌다({@link #reserveAttempt}). 로그만 남긴다(주소는 남기지 않는다). */
    // [JA] パスワードが間違った — 試行はすでに数えた({@link #reserveAttempt})。ログだけを残す(アドレスは残さない)。
    public void onFailure(String email) {
        log.debug("로그인 비밀번호 불일치");
    }

    /** 비밀번호가 맞았다 — 이번 창의 시도를 지운다. */
    // [JA] パスワードが合った — 今回のウィンドウの試行を消す。
    public void onSuccess(String email) {
        try {
            redis.delete(ATTEMPTS_KEY + idOf(email));
        } catch (DataAccessException e) {
            log.warn("로그인 시도 카운터를 지우지 못했다 — 창이 지나면 저절로 사라진다: {}", e.getClass().getSimpleName());
        }
    }

    /** Redis 키에 쓰는 표지 — 이메일 원문을 남기지 않는다(대소문자 · 공백은 같은 계정). */
    // [JA] Redis キーに使う標識 — メールアドレスの原文を残さない(大文字小文字・空白は同じアカウント)。
    static String idOf(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
