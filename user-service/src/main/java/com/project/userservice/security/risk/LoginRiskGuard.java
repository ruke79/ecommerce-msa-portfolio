package com.project.userservice.security.risk;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 위험 기반 로그인 — 비밀번호가 맞아도 <b>지금 공격이 진행 중인 모양</b>이면 한 번 더 확인한다(docs/68 §19).
 *
 * <h3>왜</h3>
 *
 * <p>계정 단위 잠금(5회)과 IP 단위 요청 제한은 "한 계정을 계속 두드리는" 공격만 막는다. 크리덴셜 스터핑은 다른 사이트에서 샌
 * "이메일 + 비밀번호" 목록을 <b>계정마다 한 번씩</b>, 여러 IP 로 넣어 본다. 계정마다 실패가 1번이라 잠금이 걸리지 않고, 맞는 조합은
 * 첫 시도에 맞는다. 그 한 번의 "성공" 을 막으려면 성공 순간에 주변 상황을 봐야 한다.
 *
 * <h3>무엇을 보나 — 실패를 기록해 둔 신호 세 가지</h3>
 * <ul>
 *   <li><b>IP 팬아웃</b> — 이 IP 에서 10분 안에 서로 다른 계정 N개 이상이 틀렸다(목록을 돌리는 출처).</li>
 *   <li><b>표적 계정</b> — 이 계정이 1시간 안에 서로 다른 IP M개 이상에서 틀렸다(분산 대입의 표적).</li>
 *   <li><b>전체 급증</b> — 최근 2분의 전체 로그인 실패가 평소를 크게 넘는다(스터핑 물결).</li>
 * </ul>
 * 그리고 <b>아는 기기</b>인가(기기 쿠키, DeviceRecognitionService). 목록으로 들어오는 공격자는 그 쿠키를 갖고 있지 않다.
 *
 * <h3>결정 — 막지 않고 확인한다</h3>
 *
 * <p>아는 기기면 통과. 처음 보는 기기인데 신호가 <b>하나라도</b> 있으면 메일로 6자리 코드를 보내 확인한다(2FA 계정은 이미 TOTP 를
 * 거치므로 여기 오지 않는다). 막아 버리면 같은 IP 를 쓰는 사람들(회사 · 통신사 NAT)과 공격 물결 중에 들어온 정상 사용자가 함께 잠긴다 —
 * 대형 서비스도 대부분 "차단" 이 아니라 "추가 확인" 을 고른다. 신호가 없으면 새 기기라도 통과한다(새 기기 알림은 그대로 나간다).
 *
 * <h3>개인정보</h3>
 *
 * <p>Redis 에는 이메일 · IP 원문을 두지 않는다 — {@code JWT_SECRET_KEY} 에서 파생한 키의 HMAC 만. 이메일은 추측 가능한 값이라 그냥
 * 해시로 두면 목록으로 되돌려 볼 수 있다. 코드도 원문이 아니라 해시로 두고, 로그 · 지표에 싣지 않는다.
 */
// [JA] リスクベースのログイン — パスワードが合っても「攻撃が進行中の形」ならもう一度確認する。IP の広がり · 標的アカウント · 全体の急増の
//      3 つの信号と、既知の端末かどうかを見る。未知の端末で信号が一つでもあれば、メールの 6 桁コードで確認する(遮断はしない)。
//      Redis にはメール · IP の原文を置かず HMAC だけ、コードもハッシュで置く。
@Slf4j
@Component
public class LoginRiskGuard {

    static final String IP_KEY = "risk:ip:";          // + hmac(ip)    → 실패한 계정 hmac 집합(10분)
    static final String ACCOUNT_KEY = "risk:acct:";   // + hmac(email) → 실패한 IP hmac 집합(1시간)
    static final String GLOBAL_KEY = "risk:global:";  // + 분 번호      → 실패 수
    static final String CHALLENGE_KEY = "risk:chal:"; // + sha256(대기 값) → uid · 코드 해시 · 시도 수
    static final String USER_FAIL_KEY = "risk:chalfail:"; // + userId → 1시간 동안 틀린 코드 수(대기 값과 무관)
    static final String CHALLENGE_METRIC = "auth.risk.challenge";

    /** 확인 대기 쿠키 — 비밀번호 단계만 통과했다는 표시. HttpOnly(JwtUtils.createCookie). */
    public static final String COOKIE = "PENDING_LOGIN_CHECK";
    public static final Duration CHALLENGE_TTL = Duration.ofMinutes(10);
    static final int MAX_ATTEMPTS = 5;
    /**
     * 사용자별 한도 — 대기 값당 5번만 세면 비밀번호를 아는 쪽이 다시 로그인할 때마다 새 5번을 얻는다(§15 의 2FA 와 같은 구멍).
     * 1시간에 10번이면 6자리 코드를 맞힐 확률은 시간당 10/1,000,000 이다.
     */
    static final int MAX_USER_FAILURES = 10;
    static final Duration USER_FAIL_WINDOW = Duration.ofHours(1);

    public enum Signal { IP_FANOUT, TARGETED_ACCOUNT, GLOBAL_SURGE }

    public record Issued(String cookieValue, List<Signal> signals) {
    }

    public sealed interface Verification permits Passed, Wrong, Expired {
    }
    public record Passed(Long userId) implements Verification {
    }
    /** @param more 이 대기 값으로 더 시도할 수 있는가 */
    public record Wrong(boolean more) implements Verification {
    }
    public record Expired() implements Verification {
    }

    private final StringRedisTemplate redis;
    private final MeterRegistry meters;
    private final byte[] hmacKey;
    private final SecureRandom random = new SecureRandom();
    private final int ipFanoutThreshold;
    private final int accountIpThreshold;
    private final int globalThreshold;

    public LoginRiskGuard(StringRedisTemplate redis, MeterRegistry meters,
            @Value("${spring.app.jwtSecret}") String jwtSecret,
            @Value("${auth.risk.ip-fanout-threshold:10}") int ipFanoutThreshold,
            @Value("${auth.risk.account-ip-threshold:3}") int accountIpThreshold,
            @Value("${auth.risk.global-failure-threshold:300}") int globalThreshold) {
        this.redis = redis;
        this.meters = meters;
        this.hmacKey = derive(jwtSecret);
        this.ipFanoutThreshold = ipFanoutThreshold;
        this.accountIpThreshold = accountIpThreshold;
        this.globalThreshold = globalThreshold;
    }

    // ── 실패 기록 ─────────────────────────────────────────────────────────

    static final Duration IP_WINDOW = Duration.ofMinutes(10);
    static final Duration ACCOUNT_WINDOW = Duration.ofHours(1);
    static final Duration GLOBAL_WINDOW = Duration.ofMinutes(5);

    /**
     * 세 기록(IP 집합 · 계정 집합 · 분 카운터)을 더하고 저마다의 창을 다시 건다 — 한 덩어리다.
     *
     * <p>🐛 예전에는 {@code SADD} 와 {@code EXPIRE} 가 따로였다 — 그 사이에 끊기면(파드 종료 · 커넥션 끊김) 만료 없는 집합이
     * 남는다. 그 IP · 계정의 신호는 <b>영영 켜진 채</b>가 되어, 아무 일 없는 사용자에게 계속 확인 코드를 요구한다(Redis 메모리도
     * 돌려받지 못한다). 셋 다 창을 매번 다시 거는 것은 예전과 같다 — 공격이 이어지는 동안 신호가 유지된다.
     * KEYS[1]=IP 집합 KEYS[2]=계정 집합 KEYS[3]=분 카운터 · ARGV[1]=계정 hmac ARGV[2]=IP hmac ARGV[3..5]=각 창(ms)
     */
    // [JA] 3 つの記録を一度に。以前は SADD と EXPIRE が別で、間で切れると期限なしの集合が残り信号が永久に点いたままになった。
    private static final DefaultRedisScript<Long> RECORD = new DefaultRedisScript<>("""
            redis.call('SADD', KEYS[1], ARGV[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            redis.call('SADD', KEYS[2], ARGV[2])
            redis.call('PEXPIRE', KEYS[2], ARGV[4])
            redis.call('INCR', KEYS[3])
            redis.call('PEXPIRE', KEYS[3], ARGV[5])
            return 1
            """, Long.class);

    /** 비밀번호가 틀린 로그인 하나를 기록한다. 기록이 실패해도 로그인 응답은 바뀌지 않는다. */
    public void recordFailure(String email, String ip) {
        try {
            String acct = hmac(normalize(email));
            String src = hmac(ip == null ? "" : ip);
            redis.execute(RECORD,
                    List.of(IP_KEY + src, ACCOUNT_KEY + acct, GLOBAL_KEY + minute(0)),
                    acct, src,
                    String.valueOf(IP_WINDOW.toMillis()),
                    String.valueOf(ACCOUNT_WINDOW.toMillis()),
                    String.valueOf(GLOBAL_WINDOW.toMillis()));
        } catch (RuntimeException e) {
            log.warn("로그인 실패 신호를 기록하지 못했다 — {}", e.getClass().getSimpleName());
        }
    }

    /** 지금 이 계정 · IP 에 걸린 신호. Redis 가 안 되면 빈 목록(막지 않는다). */
    public List<Signal> signals(String email, String ip) {
        List<Signal> out = new ArrayList<>();
        try {
            Long fanout = redis.opsForSet().size(IP_KEY + hmac(ip == null ? "" : ip));
            if (fanout != null && fanout >= ipFanoutThreshold) {
                out.add(Signal.IP_FANOUT);
            }
            Long ips = redis.opsForSet().size(ACCOUNT_KEY + hmac(normalize(email)));
            if (ips != null && ips >= accountIpThreshold) {
                out.add(Signal.TARGETED_ACCOUNT);
            }
            long global = count(GLOBAL_KEY + minute(0)) + count(GLOBAL_KEY + minute(-1));
            if (global >= globalThreshold) {
                out.add(Signal.GLOBAL_SURGE);
            }
        } catch (RuntimeException e) {
            log.warn("로그인 위험 신호를 읽지 못해 통과시켰다 — {}", e.getClass().getSimpleName());
        }
        return out;
    }

    /** 결정 — 아는 기기면 통과, 처음 보는 기기에 신호가 하나라도 있으면 확인. */
    public static boolean needsChallenge(boolean knownDevice, List<Signal> signals) {
        return !knownDevice && !signals.isEmpty();
    }

    // ── 확인 코드 ─────────────────────────────────────────────────────────

    /**
     * 확인을 시작한다 — 코드를 만들어 해시만 저장하고, 원문 코드는 호출자가 메일로만 보낸다.
     *
     * @return 대기 쿠키 값 · 6자리 코드. 코드는 로그 · 응답에 싣지 않는다.
     */
    public IssuedChallenge issue(Long userId, List<Signal> signals) {
        String pending = HexFormat.of().formatHex(randomBytes(32));
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        String key = CHALLENGE_KEY + sha256(pending);
        redis.opsForHash().putAll(key, Map.of(
                "uid", String.valueOf(userId),
                "code", sha256(pending + ":" + code),
                "tries", "0"));
        redis.expire(key, CHALLENGE_TTL);
        for (Signal s : signals) {
            meters.counter(CHALLENGE_METRIC, "signal", s.name().toLowerCase(Locale.ROOT)).increment();
        }
        return new IssuedChallenge(new Issued(pending, signals), code);
    }

    /** 원문 코드는 이 값에만 있다 — 메일로 보낸 뒤 버린다. */
    public record IssuedChallenge(Issued issued, String code) {
    }

    /**
     * 대기 값이 있으면 이번 시도를 세고 {tries, 사용자 칸, uid, 코드 해시} 를 돌려준다. 없으면(이미 태웠거나 지웠다) {} —
     * <b>되살리지 않는다.</b> 어느 쪽이든 한도를 넘으면 대기 값을 지우고 {}.
     *
     * <p>uid · 코드 해시도 여기서 함께 읽는다 — 예전에는 사용자 칸의 이름을 만들려고 스크립트 <b>앞에서</b> 해시를 한 번 더 읽었다.
     * 그 사이에 키가 지워져도 스크립트가 {} 를 돌려주어 안전하게 끝났지만, 왕복이 둘이고 "읽은 값" 과 "센 대상" 이 갈라져 있었다.
     * 코드 칸이 없는(깨진) 해시면 돌려줄 것이 셋뿐이라 호출자가 만료로 본다 — 막는 쪽이다.
     *
     * <p>사용자 칸의 이름은 스크립트 안에서 접두사 + uid 로 만든다(단일 Redis — 클러스터로 가면 두 키가 같은 슬롯이어야 한다).
     * KEYS[1]=대기 값 · ARGV[1]=대기 값당 한도 ARGV[2]=사용자 한도 ARGV[3]=사용자 칸 수명(초) ARGV[4]=사용자 칸 접두사
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final DefaultRedisScript<List<Object>> RESERVE = new DefaultRedisScript<>("""
                    local uid = redis.call('HGET', KEYS[1], 'uid')
                    if not uid then
                      return {}
                    end
                    local code = redis.call('HGET', KEYS[1], 'code')
                    local userKey = ARGV[4] .. uid
                    local n = redis.call('HINCRBY', KEYS[1], 'tries', 1)
                    local u = redis.call('INCR', userKey)
                    redis.call('EXPIRE', userKey, ARGV[3])
                    if n > tonumber(ARGV[1]) or u > tonumber(ARGV[2]) then
                      redis.call('DEL', KEYS[1])
                      return {}
                    end
                    return {n, u, uid, code}
                    """, (Class) List.class);

    /** 코드를 확인한다. 맞으면 대기 값을 태운다(한 번만 쓴다). */
    public Verification verify(String pending, String code) {
        if (pending == null || pending.isBlank() || code == null) {
            return new Expired();
        }
        String key = CHALLENGE_KEY + sha256(pending);
        // 🔒 시도를 **비교하기 전에** 센다(Lua 한 번). 🐛 예전에는 횟수를 읽고 → 비교하고 → 틀리면 더했다 — 동시에 보낸 요청이
        //    모두 같은 "0번" 을 읽어, 6자리를 한 번에 수백 개 대입할 수 있었다. 이제 한도를 넘은 시도는 비교조차 하지 않는다.
        //    맞으면 아래에서 대기 값을 태우므로 세어 둔 수는 의미가 없어진다(사용자 칸은 창이 지나면 사라진다).
        // [JA] 試行を比較する前に数える。以前は読む → 比較 → 間違えたら加算で、同時のリクエストがすべて 0 回を読んでいた。
        // 🐛 그 다음: 다섯 번째 실패가 키를 지운 뒤에 도착한 요청의 HINCRBY 가 tries 만 든 새 해시를 만들어(만료도 없이) 1 부터 다시 셌다 —
        //    비교가 한도를 넘었고 그 키는 영원히 남았다. 이제 "있으면 센다 · 한도를 넘으면 지운다" 를 Lua 한 번으로 한다.
        // [JA] 5 回目の失敗がキーを消した後に届いたリクエストの HINCRBY が tries だけの新しいハッシュを作っていた。Lua 一回で数える。
        List<Object> counted = redis.execute(RESERVE, List.of(key),
                String.valueOf(MAX_ATTEMPTS), String.valueOf(MAX_USER_FAILURES),
                String.valueOf(USER_FAIL_WINDOW.toSeconds()), USER_FAIL_KEY);
        if (counted == null || counted.size() < 4) {
            return new Expired();
        }
        long n = (Long) counted.get(0);
        long userAttempts = (Long) counted.get(1);
        String uid = String.valueOf(counted.get(2));
        String userKey = USER_FAIL_KEY + uid;
        String expected = String.valueOf(counted.get(3));
        String presented = sha256(pending + ":" + code.trim());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8))) {
            boolean more = n < MAX_ATTEMPTS && userAttempts < MAX_USER_FAILURES;
            if (!more) {
                redis.delete(key);
            }
            return new Wrong(more);
        }
        // 맞았다 — 이 시도는 실패가 아니다. 사용자 칸에서 되돌린다(다음 로그인의 확인 코드 몫을 깎지 않게).
        redis.opsForValue().decrement(userKey);
        // 한 번만 — 지우기에 성공한 요청만 통과한다(같은 코드로 동시에 두 번 와도 하나만).
        Boolean deleted = redis.delete(key);
        if (!Boolean.TRUE.equals(deleted)) {
            return new Expired();
        }
        return new Passed(Long.valueOf(uid));
    }

    // ── 도우미 ───────────────────────────────────────────────────────────

    private long count(String key) {
        String v = redis.opsForValue().get(key);
        return v == null ? 0 : Long.parseLong(v);
    }

    private static long minute(int offset) {
        return System.currentTimeMillis() / 60_000 + offset;
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    private static byte[] derive(String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal("login-risk-signals".getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("위험 신호 키를 만들지 못했다", e);
        }
    }

    /** 응답에 이 확인이 걸려 있다는 표시(쿠키 값은 응답 본문에 싣지 않는다). */
    public static Optional<String> cookieValue(jakarta.servlet.http.HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        for (jakarta.servlet.http.Cookie c : request.getCookies()) {
            if (COOKIE.equals(c.getName())) {
                return Optional.ofNullable(c.getValue());
            }
        }
        return Optional.empty();
    }
}
