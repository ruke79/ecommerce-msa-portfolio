package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.project.userservice.constants.AppRole;
import com.project.userservice.message.UserOutboxHelper;
import com.project.userservice.model.Role;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.RoleRepository;
import com.project.userservice.repository.jpa.UserRepository;

/**
 * 데모 계정 내주기 — 후보 SQL({@link DemoAccountService#CANDIDATES_SQL})은 마이그레이션한 실제 PostgreSQL 에서, 차례는 Redis INCR
 * (docs/100). 스키마를 바꾸지 않는다 — 데모 표시는 이미 있는 sign_up_method.
 *
 * <p>[JA] デモアカウントの払い出し — 候補 SQL はマイグレーション済みの実際の PostgreSQL で、順番は Redis INCR。スキーマは変えない。
 */
@Tag("containers")
@Testcontainers
@DisplayName("데모 계정 내주기 — 쓸 수 있는 데모 계정만 · 차례로 돌림(실제 SQL)")
class DemoAccountClaimTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("userdb").withUsername("product").withPassword("testpw");

    static DriverManagerDataSource ds;
    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("0").load().migrate();
        ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
    }

    final AtomicLong counter = new AtomicLong();
    StringRedisTemplate redis;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc.update("DELETE FROM users.users WHERE user_id BETWEEN 8000 AND 8999");
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(DemoAccountService.NEXT_KEY)).thenAnswer(inv -> counter.incrementAndGet());
    }

    private DemoAccountService svc(boolean enabled) {
        UserRepository users = mock(UserRepository.class);
        when(users.findById(anyLong())).thenAnswer(inv -> {
            User u = new User("x", "x@demo.invalid", "pw");
            u.setUserId(inv.getArgument(0));
            u.setSignUpMethod(User.DEMO_SIGN_UP_METHOD);
            u.setRole(new Role(AppRole.ROLE_USER));
            return Optional.of(u);
        });
        return new DemoAccountService(enabled, 5, "demo.invalid", users, mock(RoleRepository.class), null,
                mock(UserOutboxHelper.class), jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)), redis);
    }

    private static void insert(long id, String method, boolean enabled, boolean locked, boolean withdrawn) {
        jdbc.update("INSERT INTO users.users (user_id, username, email, account_non_expired, account_non_locked,"
                + " credentials_non_expired, email_verified, enabled, is_two_factor_enabled, sign_up_method, withdrawn_at)"
                + " VALUES (?, ?, ?, true, ?, true, true, ?, false, ?, " + (withdrawn ? "now()" : "NULL") + ")",
                id, "u" + id, "u" + id + "@demo.invalid", !locked, enabled, method);
    }

    private static List<Long> claim(DemoAccountService s, int times) {
        List<Long> got = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            got.add(s.claimNext().orElseThrow().getUserId());
        }
        return got;
    }

    @Test
    @DisplayName("🔴 데모 계정을 차례로 — 한 바퀴 돌면 처음 것(가장 오래전에 내준 것)")
    void rotates() {
        insert(8001, "demo", true, false, false);
        insert(8002, "demo", true, false, false);
        insert(8003, "demo", true, false, false);
        assertThat(claim(svc(true), 4)).containsExactly(8001L, 8002L, 8003L, 8001L);
    }

    @Test
    @DisplayName("🔴 보통(email · google) · 비활성 · 잠금 · 탈퇴 계정은 내주지 않는다 — 없으면 빈 값")
    void onlyUsableDemoAccounts() {
        insert(8011, "email", true, false, false);
        insert(8012, "google", true, false, false);
        insert(8013, "demo", false, false, false);
        insert(8014, "demo", true, true, false);
        insert(8015, "demo", true, false, true);
        assertThat(svc(true).claimNext()).isEmpty();

        insert(8016, "demo", true, false, false);
        assertThat(claim(svc(true), 2)).containsOnly(8016L);
    }

    @Test
    @DisplayName("Redis 가 실패해도 막지 않는다 — 아무 데모 계정이나")
    void redisFailureStillServes() {
        insert(8021, "demo", true, false, false);
        when(redis.opsForValue().increment(DemoAccountService.NEXT_KEY)).thenThrow(new IllegalStateException("down"));
        assertThat(claim(svc(true), 1)).containsExactly(8021L);
    }

    @Test
    @DisplayName("꺼져 있으면 아무것도 내주지 않는다")
    void disabledClaimsNothing() {
        insert(8031, "demo", true, false, false);
        assertThat(svc(false).claimNext()).isEmpty();
    }

    @Test
    @DisplayName("🔴 매일 초기화의 DELETE 가 실제 스키마에서 돈다 — 데모 계정의 배송지만 지우고 다른 사람 것은 그대로")
    void resetSqlRunsOnRealSchema() {
        insert(8041, "demo", true, false, false);
        insert(8042, "email", true, false, false);
        jdbc.update("INSERT INTO users.shipping_address (shipping_address_id, user_id, version, active, address1, city, state, zip_code)"
                + " VALUES (8141, 8041, 0, true, 'a', 'Seoul', 'Seoul', '01234'), (8142, 8042, 0, true, 'b', 'Seoul', 'Seoul', '01234')");
        SchedulerLease lease = new SchedulerLease(jdbc);
        com.project.userservice.client.NotificationServiceClient notifications = mock(com.project.userservice.client.NotificationServiceClient.class);
        DemoAccountReset reset = new DemoAccountReset(svc(true), lease,
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),
                new TransactionTemplate(new DataSourceTransactionManager(ds)), notifications);

        assertThat(reset.run()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.shipping_address WHERE user_id = 8041", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.shipping_address WHERE user_id = 8042", Long.class)).isEqualTo(1);
        jdbc.update("DELETE FROM users.shipping_address WHERE user_id IN (8041, 8042)");
    }
}
