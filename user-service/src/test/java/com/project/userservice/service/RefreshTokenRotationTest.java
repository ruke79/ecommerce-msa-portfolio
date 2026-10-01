package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.project.userservice.constants.AppRole;
import com.project.userservice.message.producer.SecurityAlertProducer;
import com.project.userservice.model.Role;
import com.project.userservice.model.User;
import com.project.userservice.model.redis.RefreshToken;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.repository.redis.RefreshTokenRepository;
import com.project.userservice.security.RedisSessionManager;
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.service.RefreshTokenService.Kind;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 🔴 리프레시 토큰 회전 · 재사용 감지 — <b>실제 Redis</b>(Lua 의 원자성은 모형으로 확인할 수 없다)와 실제 JWT 로(docs/68).
 *
 * <p>🐛 예전: 갱신해도 옛 토큰이 수명(14일)까지 유효했고, 재사용을 알아채지 못했고, 로그아웃은 지금 낸 토큰 하나만 지웠다.
 */
@Tag("containers")
@Testcontainers
@DisplayName("리프레시 토큰 — 회전 · 재사용 감지 (실제 Redis)")
class RefreshTokenRotationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379).withCommand("redis-server", "--requirepass", "testpw");

    private static final String SECRET = "user-test-secret-user-test-secret-0123456";
    private static final int LIFETIME_MS = 120_000;
    private static final long GRACE_MS = 400;

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate template;

    private RefreshTokenRepository repo;
    private JwtUtils jwt;
    private SimpleMeterRegistry meters;
    private RefreshTokenService service;
    private SecurityAlertProducer alerts;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));
        config.setPassword("testpw");
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void close() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        template.getConnectionFactory().getConnection().serverCommands().flushAll();
        repo = new RefreshTokenRepository(template);
        ReflectionTestUtils.setField(repo, "jwtRefreshExpirationMs", LIFETIME_MS);
        jwt = new JwtUtils(mock(RedisSessionManager.class));
        ReflectionTestUtils.setField(jwt, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(jwt, "jwtExpirationMs", 60_000);
        ReflectionTestUtils.setField(jwt, "jwtRefreshExpirationMs", LIFETIME_MS);

        User u = mock(User.class);
        Role r = mock(Role.class);
        when(r.getRoleName()).thenReturn(AppRole.ROLE_USER);
        when(u.getRole()).thenReturn(r);
        when(u.getEmail()).thenReturn("a@example.com");
        when(u.getUserId()).thenReturn(7L);
        UserRepository users = mock(UserRepository.class);
        when(users.findById(7L)).thenReturn(Optional.of(u));

        meters = new SimpleMeterRegistry();
        alerts = mock(SecurityAlertProducer.class);
        service = new RefreshTokenService(repo, users, jwt, meters, alerts,
                new com.project.userservice.security.jwt.GraceTokenCipher(SECRET), GRACE_MS, 1000);
    }

    private String login() {
        return service.createRefreshToken(7L).getToken();
    }

    private double count(String result) {
        var c = meters.find(RefreshTokenService.METRIC).tag("result", result).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("🔴 회전 — 새 토큰은 같은 묶음 · 새 세션, 옛 토큰의 키는 지워진다")
    void rotationReplacesTheToken() {
        String first = login();

        var r = service.rotate(first, 7L);

        assertThat(r.kind()).isEqualTo(Kind.ROTATED);
        assertThat(r.token()).isNotEqualTo(first);
        assertThat(jwt.getFamilyIdFromJwtToken(r.token())).isEqualTo(jwt.getFamilyIdFromJwtToken(first));
        assertThat(template.hasKey(jwt.getSessionIdFromJwtToken(first))).isFalse();
        // 🔒 저장소에는 해시만 — 원문이 아니다(§10)
        assertThat(template.opsForValue().get(jwt.getSessionIdFromJwtToken(r.token())))
                .isEqualTo(RefreshTokenRepository.digest(r.token())).isNotEqualTo(r.token());
    }

    @Test
    @DisplayName("🔴 회전해도 최초 로그인 시각은 그대로 — 절대 수명(30일)이 갱신으로 늘지 않는다(docs/68 §12)")
    void rotationKeepsAuthTime() {
        String first = login();
        long authTime = jwt.getAuthTimeFromJwtToken(first);

        var r1 = service.rotate(first, 7L);
        var r2 = service.rotate(r1.token(), 7L);

        assertThat(jwt.getAuthTimeFromJwtToken(r2.token())).isEqualTo(authTime);
    }

    @Test
    @DisplayName("🔴 절대 수명이 지난 로그인은 갱신하지 않고 묶음을 끊는다 — 정책을 줄인 뒤 남아 있던 토큰도")
    void pastAbsoluteLifetimeEndsTheSession() {
        String token = login();
        // 운영자가 절대 수명을 줄였다 — 그 전에 발급된 토큰은 서명 · 만료가 멀쩡해도 여기서 끝나야 한다.
        ReflectionTestUtils.setField(jwt, "jwtRefreshAbsoluteMs", 0L);

        var r = service.rotate(token, 7L);

        assertThat(r.kind()).isEqualTo(Kind.SESSION_ENDED);
        assertThat(r.ok()).isFalse();
        assertThat(repo.isFamilyRevoked(jwt.getFamilyIdFromJwtToken(token))).isTrue();
        assertThat(count("session_ended")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("🔴 재사용 감지 — 유예가 지난 뒤 옛 토큰이 다시 오면 묶음 전체를 끊는다(지금 토큰도 못 쓴다)")
    void reuseRevokesTheWholeFamily() throws Exception {
        String stolen = login();
        String current = service.rotate(stolen, 7L).token();
        Thread.sleep(GRACE_MS + 200);

        var attacker = service.rotate(stolen, 7L);
        var victim = service.rotate(current, 7L);

        assertThat(attacker.kind()).isEqualTo(Kind.REUSED);
        assertThat(victim.kind()).isEqualTo(Kind.REVOKED);
        assertThat(template.hasKey(jwt.getSessionIdFromJwtToken(current))).isFalse();
        assertThat(count("reused")).isEqualTo(1);
        // 계정 주인에게 한 번 알린다 — 주소는 DB 의 것, 묶음 id 원문은 프로듀서가 해시로 바꾼다
        verify(alerts, times(1)).publishRefreshReuse(7L, "a@example.com", jwt.getFamilyIdFromJwtToken(stolen));
        // 끊긴 표시는 토큰 수명만큼 남는다 — 영원히 쌓이지 않는다
        Long ttl = template.getExpire("rt:revoked:" + jwt.getFamilyIdFromJwtToken(stolen));
        assertThat(ttl).isPositive().isLessThanOrEqualTo(LIFETIME_MS / 1000);
    }

    @Test
    @DisplayName("동시 갱신(탭 두 개) — 유예 안에서는 재사용이 아니라 방금 발급한 같은 새 토큰을 받는다")
    void graceReturnsTheSameNewToken() {
        String first = login();

        var a = service.rotate(first, 7L);
        var b = service.rotate(first, 7L);

        assertThat(a.kind()).isEqualTo(Kind.ROTATED);
        assertThat(b.kind()).isEqualTo(Kind.GRACE);
        assertThat(b.token()).isEqualTo(a.token());
        assertThat(service.rotate(a.token(), 7L).kind()).as("묶음은 끊기지 않았다").isEqualTo(Kind.ROTATED);
        verify(alerts, never()).publishRefreshReuse(any(), anyString(), anyString());
    }

    /** GET · DEL 을 따로 부르면 둘 다 "있다" 를 보고 둘 다 새 토큰을 받는다. Lua 가 막는지 실제로 부딪혀 본다. */
    @Test
    @DisplayName("🔴 진짜 동시 — 같은 토큰으로 8개가 한꺼번에 와도 회전은 정확히 한 번, 나머지는 같은 새 토큰")
    void concurrentRefreshRotatesOnce() throws Exception {
        String first = login();
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<RefreshTokenService.Rotation>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return service.rotate(first, 7L);
            }));
        }
        go.countDown();
        List<RefreshTokenService.Rotation> out = new ArrayList<>();
        for (var f : results) {
            out.add(f.get(10, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertThat(out).filteredOn(r -> r.kind() == Kind.ROTATED).hasSize(1);
        String winner = out.stream().filter(r -> r.kind() == Kind.ROTATED).findFirst().orElseThrow().token();
        assertThat(out).allSatisfy(r -> {
            assertThat(r.kind()).isIn(Kind.ROTATED, Kind.GRACE);
            assertThat(r.token()).isEqualTo(winner);
        });
        assertThat(count("reused")).isZero();
        verify(alerts, never()).publishRefreshReuse(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("🔴 로그아웃은 묶음을 끊는다 — 갱신 전의 옛 토큰도, 지금 토큰도 더는 못 쓴다")
    void logoutRevokesTheFamily() throws Exception {
        String old = login();
        String current = service.rotate(old, 7L).token();
        Thread.sleep(GRACE_MS + 200);

        service.revoke(current);

        assertThat(service.rotate(current, 7L).kind()).isEqualTo(Kind.REVOKED);
        assertThat(service.rotate(old, 7L).kind()).isEqualTo(Kind.REVOKED);
    }

    @Test
    @DisplayName("회전 이전에 발급된 토큰(fam 없음)은 키가 남아 있어도 받지 않는다 — 배포 뒤 한 번 다시 로그인")
    void legacyTokenIsRejected() {
        String legacy = io.jsonwebtoken.Jwts.builder().subject("a@example.com").claim("uid", 7L)
                .claim("sessionId", "legacy-session").claim(JwtUtils.TOKEN_TYPE_CLAIM, JwtUtils.REFRESH)
                .issuedAt(new java.util.Date()).expiration(new java.util.Date(System.currentTimeMillis() + 60_000))
                .signWith(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"))
                .compact();
        repo.save(new RefreshToken("legacy-session", "a@example.com", legacy));

        assertThat(service.rotate(legacy, 7L).kind()).isEqualTo(Kind.LEGACY);
    }

    @Test
    @DisplayName("다른 요청이 회전 중인데 끝나지 않으면 거절만 한다 — 도둑이라 단정하지 않는다(묶음을 끊지 않는다)")
    void stuckPendingIsNotTreatedAsTheft() {
        String first = login();
        String sessionId = jwt.getSessionIdFromJwtToken(first);
        template.delete(sessionId);
        template.opsForValue().set("rt:grace:" + sessionId, RefreshTokenRepository.PENDING);

        assertThat(service.rotate(first, 7L).kind()).isEqualTo(Kind.REJECTED);
        assertThat(repo.isFamilyRevoked(jwt.getFamilyIdFromJwtToken(first))).isFalse();
    }

    @Test
    @DisplayName("저장된 값과 다르면 거절(묶음은 끊지 않는다) — 키가 있는가가 아니라 값이 같은가를 본다")
    void mismatchedValueIsRejected() {
        String first = login();
        template.opsForValue().set(jwt.getSessionIdFromJwtToken(first), "something-else");

        assertThat(service.rotate(first, 7L).kind()).isEqualTo(Kind.REJECTED);
        assertThat(repo.isFamilyRevoked(jwt.getFamilyIdFromJwtToken(first))).isFalse();
    }

    // ── 모든 기기에서 로그아웃(docs/68 §8) ─────────────────────────────────────

    @Test
    @DisplayName("🔴 모든 기기에서 로그아웃 — 로그인 둘(하나는 갱신까지 한)이 모두 끊기고, 재사용 알림은 없다")
    void revokeAllCutsEveryLogin() {
        String phone = login();
        String phoneNow = service.rotate(phone, 7L).token();
        String laptop = login();

        int revoked = service.revokeAll(7L);

        assertThat(revoked).isEqualTo(2);
        assertThat(service.rotate(phoneNow, 7L).kind()).isEqualTo(Kind.REVOKED);
        assertThat(service.rotate(laptop, 7L).kind()).isEqualTo(Kind.REVOKED);
        assertThat(template.hasKey("rt:user:7")).isFalse();
        verify(alerts, never()).publishRefreshReuse(any(), anyString(), anyString());
        assertThat(meters.find("auth.logout.all").counter().count()).isEqualTo(1);

        // 다시 로그인하면 새 묶음 — 정상으로 쓸 수 있다
        assertThat(service.rotate(login(), 7L).kind()).isEqualTo(Kind.ROTATED);
    }

    @Test
    @DisplayName("다른 사용자의 로그인은 건드리지 않는다")
    void revokeAllIsPerUser() {
        String mine = login();
        repo.addUserFamily(8L, "someone-else");
        template.opsForValue().set("rt:fam:someone-else", "their-session");

        service.revokeAll(7L);

        assertThat(service.rotate(mine, 7L).kind()).isEqualTo(Kind.REVOKED);
        assertThat(repo.isFamilyRevoked("someone-else")).isFalse();
        assertThat(template.opsForSet().members("rt:user:8")).containsExactly("someone-else");
    }

    @Test
    @DisplayName("색인은 끝난 로그인을 로그인 때 치운다 — 매일 로그인해도 끝없이 자라지 않는다")
    void indexIsPrunedOnLogin() {
        String first = login();
        service.revoke(first);           // 로그아웃 — 그 묶음은 끝났다
        String second = login();

        assertThat(template.opsForSet().members("rt:user:7"))
                .containsExactly(jwt.getFamilyIdFromJwtToken(second));
        Long ttl = template.getExpire("rt:user:7");
        assertThat(ttl).isPositive().isLessThanOrEqualTo(LIFETIME_MS / 1000);
    }

    @Test
    @DisplayName("끊을 것이 없으면 0 — 오류가 아니다")
    void revokeAllWithNothingIsZero() {
        assertThat(service.revokeAll(7L)).isZero();
    }

    // ── 해시만 저장(docs/68 §10) ─────────────────────────────────────────────

    /** Redis 의 모든 키와 값 — 문자열 · 집합. */
    private java.util.List<String> dump() {
        java.util.List<String> all = new java.util.ArrayList<>();
        for (String key : template.keys("*")) {
            all.add(key);
            switch (template.type(key)) {
                case STRING -> all.add(template.opsForValue().get(key));
                case SET -> all.addAll(template.opsForSet().members(key));
                default -> { }
            }
        }
        return all;
    }

    @Test
    @DisplayName("🔴 Redis 를 통째로 들여다봐도 리프레시 토큰 원문이 없다 — 동시 갱신 유예 중에도")
    void redisHoldsNoTokenInTheClear() {
        String first = login();
        String second = service.rotate(first, 7L).token();
        // 유예 중(두 번째 탭이 같은 토큰을 받는 20초) — 여기서 원문을 두기 쉽다
        assertThat(service.rotate(first, 7L).kind()).isEqualTo(Kind.GRACE);
        String laptop = login();

        java.util.List<String> everything = dump();

        for (String token : java.util.List.of(first, second, laptop)) {
            String signature = token.substring(token.lastIndexOf('.') + 1);
            assertThat(everything).as("토큰 원문(서명 부분)이 Redis 에 있다").noneMatch(v -> v != null && v.contains(signature));
        }
    }

    @Test
    @DisplayName("유예 값이 위변조됐으면 거절한다 — 재사용 증거는 아니므로 묶음은 끊지 않는다")
    void tamperedGraceIsRejectedWithoutRevoking() {
        String first = login();
        String sessionId = jwt.getSessionIdFromJwtToken(first);
        service.rotate(first, 7L);
        template.opsForValue().set("rt:grace:" + sessionId, "v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

        assertThat(service.rotate(first, 7L).kind()).isEqualTo(Kind.REJECTED);
        assertThat(repo.isFamilyRevoked(jwt.getFamilyIdFromJwtToken(first))).isFalse();
    }
}
