package com.project.userservice.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.message.dto.request.UserCreatedRequest;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.security.CustomAuthenticationProvider;
import com.project.userservice.security.LoginAttemptService;
import com.project.userservice.security.service.UserDetailsServiceImpl;

/**
 * 데모 사용자 생성기(ops/tools/generate-demo-users.py, docs/79)가 만든 계정이 <b>앱의 로그인 판정을 그대로 통과</b>하는지.
 *
 * <p>Flyway 로 올린 실제 users 스키마에 생성기가 낸 SQL 을 psql 로 흘려 넣고(ops/48 이 하는 것과 같다), 운영의
 * {@link CustomAuthenticationProvider} · {@link UserDetailsServiceImpl} · Hibernate 매핑(validate)으로 읽는다. 손으로 적은
 * 기대값이 아니라 앱 코드가 판정하므로, 열 하나(enabled · 해시 형식 · role)가 어긋나도 여기서 걸린다.
 *
 * <p>[JA] デモユーザー生成器が作ったアカウントが、アプリのログイン判定をそのまま通るか。生成器の SQL を本物の users スキーマに流し込み、
 * 本番の CustomAuthenticationProvider で認証する。
 */
@Tag("containers")
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // 생성기가 커밋한 행을 그대로 읽는다
@DisplayName("데모 사용자 생성기 — 앱의 로그인 판정을 통과하는 계정 (docs/79)")
class DemoUserGeneratorIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("userdb").withUsername("product").withPassword("testpw");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("eureka.client.enabled", () -> false);
    }

    /** 앱 전체가 아니라 JPA 만 — 사용자 리포지토리와 엔티티. 앱 클래스의 감사(auditAwareImpl) · Feign 은 끌어오지 않는다. */
    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.project.userservice.model")
    @EnableJpaRepositories(basePackageClasses = UserRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = UserRepository.class))
    static class JpaOnly {
    }

    private static final String PASSWORD = "Demo-Login-79!";
    private static final long OFFSET = 7_000_000_000L;
    private static final int USERS = 25;
    private static final int CHUNK = 10;
    private static final String HASH = new BCryptPasswordEncoder().encode(PASSWORD);

    @Autowired
    UserRepository users;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager tx;

    private CustomAuthenticationProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        // 운영과 같은 판정 — 잠금 기록만 가짜(Redis 없이). 잠기지 않은 상태를 돌려준다.
        provider = new CustomAuthenticationProvider(new UserDetailsServiceImpl(users), new BCryptPasswordEncoder(),
                Mockito.mock(LoginAttemptService.class));
        sql("DELETE FROM users.known_device; DELETE FROM users.outbox_event; DELETE FROM users.shipping_address; "
                + "DELETE FROM users.users WHERE user_id >= " + OFFSET);
    }

    // ── 실행 도우미 ────────────────────────────────────────────────────────

    /** 생성기를 돌려 나온 SQL 을 컨테이너의 psql 로 — ops/48 의 load 와 같은 경로(stdin 대신 파일). */
    private static void load(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("python3", "-I",
                repoRoot().resolve("ops/tools/generate-demo-users.py").toString()));
        cmd.addAll(List.of(args));
        Path out = Files.createTempFile("demo-users", ".sql");
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectOutput(out.toFile()).redirectErrorStream(false);
        pb.environment().put("DEMO_PASSWORD_HASH", HASH);
        Process p = pb.start();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.exitValue()).as("생성기 실패: %s", err).isZero();
        postgres.copyFileToContainer(MountableFile.forHostPath(out), "/tmp/demo.sql");
        ExecResult r = postgres.execInContainer("psql", "-U", "product", "-d", "userdb", "-v", "ON_ERROR_STOP=1",
                "-q", "-f", "/tmp/demo.sql");
        assertThat(r.getExitCode()).as("psql: %s", r.getStderr()).isZero();
        Files.delete(out);
    }

    private static void loadAll() throws Exception {
        for (int i = 0; i * CHUNK < USERS; i++) {
            load("--users", String.valueOf(USERS), "--chunk", String.valueOf(CHUNK), "--chunk-index", String.valueOf(i));
        }
    }

    private static long count(String query) throws Exception {
        try (Connection c = db(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void sql(String statements) throws Exception {
        try (Connection c = db(); Statement s = c.createStatement()) {
            s.execute(statements);
        }
    }

    private static Connection db() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /** 운영의 UserDetailsServiceImpl 은 @Transactional 프록시 안에서 돈다 — 같은 조건으로(역할은 LAZY). */
    private Authentication signIn(String email, String password) {
        return new org.springframework.transaction.support.TransactionTemplate(tx)
                .execute(s -> provider.authenticate(new UsernamePasswordAuthenticationToken(email, password)));
    }

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("settings.gradle"))) {
            p = p.getParent();
        }
        return p;
    }

    // ── 테스트 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 만든 계정은 운영의 로그인 판정(CustomAuthenticationProvider)을 통과하고 ROLE_USER 다 — 틀린 비밀번호는 거절")
    void generatedUsersCanSignIn() throws Exception {
        loadAll();

        Authentication auth = signIn("demo0000003@demo.invalid", PASSWORD);
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
        assertThat(signIn("demo0000024@demo.invalid", PASSWORD).isAuthenticated()).as("마지막 묶음(일부만 찬)도").isTrue();
        assertThatThrownBy(() -> signIn("demo0000003@demo.invalid", "wrong-password"))
                .isInstanceOf(BadCredentialsException.class);

        // Hibernate(validate)로 읽힌다 — 2FA 꺼짐, 가입일은 재조정기 창(7일) 밖.
        User u = users.findByEmail("demo0000003@demo.invalid").orElseThrow();
        assertThat(u.getUserId()).isEqualTo(OFFSET + 3);
        assertThat(u.isTwoFactorEnabled()).isFalse();
        assertThat(u.getCreatedAt()).isBefore(LocalDateTime.now().minusDays(7));
        assertThat(count("SELECT count(*) FROM users.users WHERE email LIKE '%@demo.invalid'")).isEqualTo(USERS);
    }

    @Test
    @DisplayName("🔴 사람마다 user-created 아웃박스 행 하나(STARTED) — 페이로드는 소비자 DTO 로 읽힌다 · 배송지 하나")
    void writesOneUserCreatedEventAndAddressEach() throws Exception {
        loadAll();

        assertThat(count("SELECT count(*) FROM users.outbox_event WHERE event_type = 'user-created' "
                + "AND aggregate_type = 'User' AND outbox_status = 'STARTED'")).isEqualTo(USERS);
        try (Connection c = db(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT aggregate_id, payload::text FROM users.outbox_event "
                     + "WHERE aggregate_id = '" + (OFFSET + 7) + "'")) {
            assertThat(rs.next()).isTrue();
            UserCreatedRequest dto = new ObjectMapper().readValue(rs.getString(2), UserCreatedRequest.class);
            assertThat(dto.getUserId()).isEqualTo(OFFSET + 7);
            assertThat(dto.getNickname()).endsWith("0000007");
            assertThat(rs.getString(2)).as("이메일은 싣지 않는다(docs/48 §6-⑥)").doesNotContain("@");
        }
        assertThat(count("SELECT count(*) FROM users.shipping_address WHERE active AND user_id >= " + OFFSET))
                .isEqualTo(USERS);
    }

    @Test
    @DisplayName("다시 실행해도 같다 — 사용자 · 배송지 · 이벤트가 두 번 생기지 않는다(끊긴 뒤 이어 넣기)")
    void rerunIsIdempotent() throws Exception {
        loadAll();
        load("--users", String.valueOf(USERS), "--chunk", String.valueOf(CHUNK), "--chunk-index", "1");
        loadAll();

        assertThat(count("SELECT count(*) FROM users.users WHERE user_id >= " + OFFSET)).isEqualTo(USERS);
        assertThat(count("SELECT count(*) FROM users.shipping_address WHERE user_id >= " + OFFSET)).isEqualTo(USERS);
        assertThat(count("SELECT count(*) FROM users.outbox_event")).isEqualTo(USERS);
    }

    @Test
    @DisplayName("🔴 purge 는 범위 안의 @demo.invalid 만 — 데모 중 생긴 기기 행까지 지우고, 범위 안의 진짜 사용자 · 범위 밖은 남긴다")
    void purgeRemovesOnlyDemoUsers() throws Exception {
        loadAll();
        // 범위 안이지만 데모 도메인이 아닌 사용자(범위를 잘못 준 경우) · 범위 밖 사용자 · 데모 사용자의 로그인 흔적
        sql("INSERT INTO users.users (user_id, username, email, enabled, email_verified, account_non_expired, "
                + "account_non_locked, credentials_non_expired, is_two_factor_enabled, created_at) VALUES "
                + "(" + (OFFSET + 100) + ", 'realinside', 'real@example.com', true, true, true, true, true, false, now()), "
                + "(42, 'realoutside', 'outside@example.com', true, true, true, true, true, false, now())");
        sql("INSERT INTO users.known_device (user_id, token_hash, first_seen_at, last_seen_at) VALUES ("
                + (OFFSET + 1) + ", repeat('a', 64), now(), now())");
        // purge 범위를 일부러 넓게 — 진짜 사용자(OFFSET+100)가 그 안에 든다.
        load("--users", "1000", "--purge");

        assertThat(count("SELECT count(*) FROM users.users WHERE email LIKE '%@demo.invalid'")).isZero();
        assertThat(count("SELECT count(*) FROM users.known_device")).isZero();
        assertThat(count("SELECT count(*) FROM users.shipping_address WHERE user_id >= " + OFFSET)).isZero();
        assertThat(count("SELECT count(*) FROM users.outbox_event WHERE outbox_status <> 'COMPLETED'"))
                .as("아직 안 나간 user-created 도 함께 — 사용자 없는 이벤트를 남기지 않는다").isZero();
        assertThat(count("SELECT count(*) FROM users.users WHERE email IN ('real@example.com', 'outside@example.com')"))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("해시가 앱의 형식($2a$10$)이 아니면 생성기가 아무것도 내지 않는다")
    void rejectsForeignHashFormat() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("python3", "-I",
                repoRoot().resolve("ops/tools/generate-demo-users.py").toString(), "--users", "3", "--chunk-index", "0");
        pb.environment().put("DEMO_PASSWORD_HASH", "{bcrypt}" + HASH);
        Process p = pb.start();
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isNotZero();
        assertThat(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEmpty();
    }
}
