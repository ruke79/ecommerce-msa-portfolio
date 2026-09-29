package com.project.userservice.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * <p>6자리 TOTP 는 100만 가지다. 횟수 제한이 없으면 5분 안에 훑을 수 있다. 토큰마다 실패를
 * {@value #MAX_ATTEMPTS} 번까지 세고 넘으면 그 토큰을 태운다 — 처음(비밀번호)부터 다시 해야 하고, 거기에는
 * 로그인 잠금(docs/27)이 걸려 있다. 성공하면 곧바로 태워 같은 토큰으로 세션을 두 번 받지 못하게 한다.
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

    /** 유효하고(서명·만료·용도) 아직 타지 않은 토큰이면 그 사용자 id. */
    public Optional<Long> userIdOf(String token) {
        Claims claims = parse(token);
        if (claims == null || attempts(claims.getId()) >= MAX_ATTEMPTS) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(claims.getSubject()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** 코드가 틀렸다. @return 이 토큰으로 더 시도할 수 있으면 true */
    public boolean recordFailure(String token) {
        Claims claims = parse(token);
        if (claims == null) {
            return false;
        }
        String k = KEY_PREFIX + claims.getId();
        Long n = redis.opsForValue().increment(k);
        redis.expire(k, Duration.ofMillis(TTL_MS));
        return n != null && n < MAX_ATTEMPTS;
    }

    /** 통과했다 — 같은 토큰을 다시 쓰지 못하게 태운다. */
    public void consume(String token) {
        Claims claims = parse(token);
        if (claims != null) {
            redis.opsForValue().set(KEY_PREFIX + claims.getId(), String.valueOf(MAX_ATTEMPTS),
                    Duration.ofMillis(TTL_MS));
        }
    }

    private int attempts(String jti) {
        String v = redis.opsForValue().get(KEY_PREFIX + jti);
        try {
            return v == null ? 0 : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return MAX_ATTEMPTS;
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
