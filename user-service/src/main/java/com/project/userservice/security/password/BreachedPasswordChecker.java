package com.project.userservice.security.password;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 새 비밀번호가 <b>이미 유출된 비밀번호</b>이거나 너무 흔한지 본다 — 가입 · 변경 · 재설정에서(docs/68 §18).
 *
 * <h3>왜</h3>
 *
 * <p>크리덴셜 스터핑은 다른 사이트에서 샌 "이메일 + 비밀번호" 목록을 그대로 넣어 본다. 사용자가 그 비밀번호를 여기서도 쓰면, 우리 쪽에
 * 아무 결함이 없어도 계정이 넘어간다. 길이 · 문자 종류 규칙은 이것을 막지 못한다 — {@code P@ssw0rd!} 는 규칙을 다 지키지만 유출 목록의
 * 맨 위에 있다. NIST SP 800-63B 도 구성 규칙 대신 "알려진 유출 목록과 대조" 를 요구한다.
 *
 * <h3>어떻게 — 비밀번호를 밖으로 보내지 않고</h3>
 *
 * <p>Have I Been Pwned 의 Pwned Passwords range API(k-익명성): 비밀번호의 SHA-1 중 <b>앞 5글자만</b> 보내고, 그 접두사를 가진 해시
 * 뒷부분 수백 개를 받아 <b>우리 쪽에서</b> 맞춰 본다. 서비스는 어느 것이 우리 사용자의 것인지 모른다. {@code Add-Padding} 으로 응답
 * 크기까지 고르게 해 접두사별 응답 길이로도 추측하지 못하게 한다(패딩 줄은 개수 0).
 *
 * <h3>API 가 안 될 때</h3>
 *
 * <p>막지 않는다(fail-open) — 외부 서비스 장애로 가입 · 재설정이 멈추면 안 된다. 대신 지표({@value #UNAVAILABLE_METRIC})와 경고 로그를
 * 남기고, <b>로컬 목록</b>(흔한 비밀번호 100여 개)은 언제나 막는다. 끄려면 {@code auth.breached-password.enabled=false}.
 */
// [JA] 新しいパスワードが漏えい済み · ありふれたものかを見る(登録 · 変更 · 再設定)。SHA-1 の先頭 5 文字だけを送り(k-匿名性)、
//      照合は自分側で行う。API が使えなければ止めない(fail-open)が、ローカルのありふれたパスワード一覧は常に防ぐ。
@Slf4j
@Component
public class BreachedPasswordChecker {

    static final String UNAVAILABLE_METRIC = "auth.breached_password.unavailable";
    static final String REJECTED_METRIC = "auth.breached_password.rejected";

    /** 접두사(대문자 hex 5자)로 범위를 받아 온다 — 테스트가 갈아 끼우는 이음새. */
    @FunctionalInterface
    public interface RangeFetcher {
        String fetch(String prefix) throws IOException, InterruptedException;
    }

    public enum Verdict { OK, COMMON, BREACHED, UNKNOWN }

    private final Set<String> common;
    private final boolean enabled;
    private final RangeFetcher fetcher;
    private final MeterRegistry meters;

    @Autowired
    public BreachedPasswordChecker(
            @Value("${auth.breached-password.enabled:true}") boolean enabled,
            @Value("${auth.breached-password.api-url:https://api.pwnedpasswords.com/range/}") String apiUrl,
            @Value("${auth.breached-password.timeout:PT2S}") Duration timeout,
            MeterRegistry meters) {
        this(enabled, httpFetcher(apiUrl, timeout), meters);
    }

    BreachedPasswordChecker(boolean enabled, RangeFetcher fetcher, MeterRegistry meters) {
        this.enabled = enabled;
        this.fetcher = fetcher;
        this.meters = meters;
        this.common = loadCommon();
    }

    /** 유출 · 흔한 비밀번호면 {@link BreachedPasswordException}. 판단할 수 없으면 통과(fail-open). */
    public void assertAcceptable(String rawPassword) {
        Verdict v = check(rawPassword);
        if (v == Verdict.BREACHED || v == Verdict.COMMON) {
            meters.counter(REJECTED_METRIC, "reason", v.name().toLowerCase(Locale.ROOT)).increment();
            throw new BreachedPasswordException();
        }
    }

    public Verdict check(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            return Verdict.OK;   // 비어 있는지는 각 경로의 입력 검사가 본다
        }
        if (common.contains(rawPassword.toLowerCase(Locale.ROOT))) {
            return Verdict.COMMON;
        }
        if (!enabled) {
            return Verdict.OK;
        }
        String hash = sha1Hex(rawPassword);
        String prefix = hash.substring(0, 5);
        String suffix = hash.substring(5);
        try {
            String body = fetcher.fetch(prefix);
            for (String line : body.split("\r?\n")) {
                int colon = line.indexOf(':');
                if (colon == 35 && line.regionMatches(true, 0, suffix, 0, 35)) {
                    long count = parseCount(line.substring(colon + 1));
                    // 패딩 줄은 개수가 0 이다 — 진짜 유출이 아니다.
                    return count > 0 ? Verdict.BREACHED : Verdict.OK;
                }
            }
            return Verdict.OK;
        } catch (IOException | RuntimeException e) {
            meters.counter(UNAVAILABLE_METRIC).increment();
            log.warn("유출 비밀번호 확인을 하지 못해 통과시켰다 — {}", e.getClass().getSimpleName());
            return Verdict.UNKNOWN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Verdict.UNKNOWN;
        }
    }

    private static long parseCount(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String sha1Hex(String raw) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-1 은 모든 JVM 에 있다
        }
    }

    private static Set<String> loadCommon() {
        Set<String> set = new HashSet<>();
        try (InputStream in = BreachedPasswordChecker.class.getResourceAsStream("/security/common-passwords.txt")) {
            if (in == null) {
                throw new IllegalStateException("security/common-passwords.txt 가 없다");
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String t = line.strip();
                    if (!t.isEmpty() && !t.startsWith("#")) {
                        set.add(t.toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return Set.copyOf(set);
    }

    private static RangeFetcher httpFetcher(String apiUrl, Duration timeout) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
        return prefix -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl + prefix))
                    .timeout(timeout)
                    .header("Add-Padding", "true")
                    .header("User-Agent", "ecommerce-msa-user-service")
                    .GET().build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                throw new IOException("HTTP " + res.statusCode());
            }
            return res.body();
        };
    }
}
