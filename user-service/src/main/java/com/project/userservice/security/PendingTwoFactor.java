package com.project.userservice.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;

/**
 * 2단계 인증 <b>대기</b> 토큰 — 첫 단계(비밀번호·소셜)는 통과했고 코드 확인이 남은 상태.
 *
 * <h3>🔴 왜 따로 두는가 — 대기 토큰이 곧 액세스 토큰이었다</h3>
 *
 * <p>예전 두 경로 모두 2FA 를 우회할 수 있었다.
 * <ul>
 *   <li><b>비밀번호 로그인</b>은 2FA 계정에도 액세스 토큰(응답 헤더)과 REFRESH 쿠키를 <b>먼저</b> 내줬다. 코드
 *       입력을 기다린 것은 프론트뿐이었다 — 비밀번호만 아는 사람은 응답 헤더의 토큰을 그대로 쓰면 됐다.</li>
 *   <li><b>소셜 로그인</b>은 세션 대신 보류 쿠키를 심었지만, 그 쿠키의 값이 <b>같은 키로 서명한 보통 JWT</b>
 *       ({@code uid} 포함)였다. 게이트웨이는 서명과 {@code uid} 만 보므로 그 값을 Bearer 로 보내면 통과했다.</li>
 * </ul>
 *
 * <p>그래서 대기 토큰은 <b>액세스 토큰 키에서 파생한 별도 키</b>로 서명한다. 게이트웨이·chat·user-service
 * 필터 등 액세스 토큰을 검증하는 모든 곳이 <b>아무것도 고치지 않고</b> 이 토큰을 서명 불일치로 거부한다.
 * {@code uid} 클레임도 넣지 않는다(이중 잠금).
 *
 * <h3>시도 횟수 · 1회용</h3>
 *
 * <p>6자리 TOTP 는 100만 가지다. 횟수 제한이 없으면 5분 안에 훑을 수 있다. 토큰마다 시도를
 * {@value #MAX_ATTEMPTS} 번까지 세고 넘으면 그 토큰을 태운다 — 처음(비밀번호)부터 다시 해야 하고, 거기에는
 * 로그인 잠금(docs/27)이 걸려 있다. 성공하면 곧바로 태워 같은 토큰으로 세션을 두 번 받지 못하게 한다.
 *
 * <p>🐛 예전에는 <b>읽고(userIdOf) → 검증하고 → 틀리면 더하는(recordFailure)</b> 순서였다 — 코드 수십 개를 동시에 보내면 모두
 * "아직 0번" 을 읽고 전부 검증을 받았다(토큰 하나로 5번이 아니라 보낸 만큼). 증가에도 틈이 있었다: {@code INCR} 다음 줄의
 * {@code expire} 사이에 끊기면 TTL 없는 키가 남아 그 사용자의 2FA 가 영영 막혔다. 지금은 {@link #reserveAttempt} 가
 * <b>검증 전에</b> Lua 한 번(증가 + 첫 시도면 TTL)으로 센다 — LoginAttemptService · CurrentPasswordGuard · TotpReplayGuard 와 같은 모양.
 */
@Slf4j
@Component
public class PendingTwoFactor {

    /** 쿠키 이름 — 소셜 경로(OAuth2SuccessHandler)와 비밀번호 경로(AuthLoginFilter)가 같이 쓴다. */
    public static final String COOKIE = "PENDING_2FA";
    public static final long TTL_MS = 5 * 60 * 1000L;
    static final int MAX_ATTEMPTS = 5;
    static final String PURPOSE = "2fa_pending";
    private static final String KEY_PREFIX = "2fa:pending:";

    /** 이번 시도를 세고(첫 시도면 토큰 수명만큼 TTL) 지금까지의 수를 돌려준다 — 읽기 · 증가 · 만료가 한 덩어리다. */
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return n
            """, Long.class);

    private final SecretKey key;
    private final StringRedisTemplate redis;

    public PendingTwoFactor(@Value("${spring.app.jwtSecret}") String jwtSecret, StringRedisTemplate redis) {
        this.key = derive(jwtSecret);
        this.redis = redis;
    }

    /** 액세스 토큰 키에서 용도가 다른 키를 파생한다 — 같은 비밀 하나로 운영하되 서로 섞이지 않는다. */
    private static SecretKey derive(String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return new SecretKeySpec(mac.doFinal(PURPOSE.getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("2FA 대기 토큰 키를 만들지 못했다", e);
        }
    }

    /** 대기 토큰을 만든다. 세션을 등록하지 않는다 — 아직 로그인한 것이 아니다. */
    public String issue(Long userId) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString())
                .claim("purpose", PURPOSE)
                .issuedAt(new Date(now))
                .expiration(new Date(now + TTL_MS))
                .signWith(key)
                .compact();
    }

    /**
     * 이번 시도의 결과 — 누구인지와, 이 토큰으로 <b>더</b> 시도할 수 있는지.
     *
     * @param userId         대기 중인 사용자
     * @param moreAfterThis  이번이 마지막 시도가 아니면 true(틀렸을 때의 안내 문구가 갈린다)
     */
    public record Attempt(long userId, boolean moreAfterThis) {
    }

    /**
     * 코드를 <b>검증하기 전에</b> 부른다 — 이번 시도를 토큰의 몫에서 먼저 센다(Lua 한 번).
     *
     * <p>비어 있으면 그 토큰으로는 더 할 수 없다: 서명 · 만료 · 용도가 맞지 않거나, 이미 탔거나
     * ({@link #consume}), 시도를 {@value #MAX_ATTEMPTS} 번 다 썼다. 맞았으면 {@link #consume} 으로 태운다.
     *
     * <p>🐛 읽고-검증하고-더하는 순서였을 때는 동시에 보낸 코드가 모두 "아직 0번" 을 읽고 전부 검증됐다.
     */
    // [JA] 検証の前に呼ぶ — 今回の試行を先に数える(Lua 一回)。空なら、そのトークンではもう試せない。
    public Optional<Attempt> reserveAttempt(String token) {
        Claims claims = parse(token);
        if (claims == null) {
            return Optional.empty();
        }
        long userId;
        try {
            userId = Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        Long n = redis.execute(RESERVE, List.of(KEY_PREFIX + claims.getId()), String.valueOf(TTL_MS));
        if (n == null || n > MAX_ATTEMPTS) {
            return Optional.empty();
        }
        return Optional.of(new Attempt(userId, n < MAX_ATTEMPTS));
    }

    /** 통과했다(또는 더 받지 않는다) — 같은 토큰을 다시 쓰지 못하게 태운다: 다음 {@link #reserveAttempt} 는 한도를 넘는다. */
    public void consume(String token) {
        Claims claims = parse(token);
        if (claims != null) {
            redis.opsForValue().set(KEY_PREFIX + claims.getId(), String.valueOf(MAX_ATTEMPTS),
                    Duration.ofMillis(TTL_MS));
        }
    }

    private Claims parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return PURPOSE.equals(claims.get("purpose")) && claims.getId() != null ? claims : null;
        } catch (JwtException | IllegalArgumentException e) {
            // 만료·위조·형식 오류를 구분해 알려주지 않는다 — 어차피 처음부터 다시 하면 된다.
            return null;
        }
    }
}
