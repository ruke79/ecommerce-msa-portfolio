package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.project.common.constants.event.CustomerTier;
import com.project.userservice.repository.jpa.UserRepository;

/**
 * 등급 배치가 겹칠 때 — 실제 PostgreSQL(Flyway V1 ~ V17).
 *
 * <p>🐛 예전: 레플리카 둘이 같은 시각에 돌아, 늦게 끝난 옛 회차가 새 회차의 VIP 를 옛 값으로 덮고, 새 회차의 강등이 그 행을
 * "손대지 않은 행" 으로 읽어 BASIC 으로 내렸다. 임대(V17)로 한 회차만 돌고, 옛 회차는 새 회차의 값을 덮지 못한다.
 *
 * <p>[JA] 等級バッチが重なるとき — 実 PostgreSQL。リースで一回分だけが走り、古い回は新しい回の値を上書きできない。
 */
@Tag("containers")
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("등급 배치 겹침 — 임대 · 옛 회차가 새 회차를 덮지 않는다(실제 PostgreSQL)")
class CustomerTierConcurrencyTest {

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

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.project.userservice.model")
    @EnableJpaRepositories(basePackageClasses = UserRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = UserRepository.class))
    static class JpaOnly {
    }

    private static final long USER = 8_100_000_001L;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM users.scheduler_lock");
        jdbc.update("DELETE FROM users.users WHERE user_id = ?", USER);
        jdbc.update("INSERT INTO users.users (user_id, username, email, account_non_expired, account_non_locked, "
                + "credentials_non_expired, email_verified, enabled, is_two_factor_enabled) "
                + "VALUES (?, 'tier-x', 'tier-x@example.com', true, true, true, true, true, false)", USER);
    }

    @Test
    @DisplayName("🔴 임대 — 한 파드가 쥐면 다른 파드는 못 잡고, 풀거나 만료되면 잡는다")
    void leaseIsExclusive() {
        SchedulerLease podA = new SchedulerLease(jdbc);
        SchedulerLease podB = new SchedulerLease(jdbc);

        assertThat(podA.tryAcquire("customer-tier", Duration.ofHours(2))).isTrue();
        assertThat(podB.tryAcquire("customer-tier", Duration.ofHours(2))).isFalse();

        podB.release("customer-tier");   // 남의 임대는 풀지 못한다
        assertThat(podB.tryAcquire("customer-tier", Duration.ofHours(2))).isFalse();

        podA.release("customer-tier");
        assertThat(podB.tryAcquire("customer-tier", Duration.ofSeconds(0))).isTrue();   // 길이 0 = 곧바로 만료
        assertThat(podA.tryAcquire("customer-tier", Duration.ofHours(2))).isTrue();
    }

    @Test
    @DisplayName("🔴 옛 회차는 새 회차가 매긴 등급을 덮지 못한다 — 0 을 돌려주고 새 값이 남는다")
    void olderRunCannotOverwriteNewer() {
        LocalDateTime older = LocalDateTime.of(2026, 10, 14, 19, 10, 0);
        LocalDateTime newer = older.plusSeconds(3);

        assertThat(users.updateTier(USER, CustomerTier.VIP, 12, 900_000, newer)).isEqualTo(1);
        assertThat(users.updateTier(USER, CustomerTier.BASIC, 1, 10, older)).isZero();

        var row = jdbc.queryForMap("SELECT tier, recent_paid_amount, tier_computed_at FROM users.users WHERE user_id = ?", USER);
        assertThat(row.get("tier")).isEqualTo("VIP");
        assertThat(((Number) row.get("recent_paid_amount")).longValue()).isEqualTo(900_000);

        // 새 회차의 강등은 이 행을 손대지 않은 행으로 읽지 않는다
        assertThat(users.demoteStale(newer, newer.plusMinutes(1))).isZero();
        assertThat(jdbc.queryForObject("SELECT tier FROM users.users WHERE user_id = ?", String.class, USER)).isEqualTo("VIP");
    }

    @Test
    @DisplayName("같은 회차 · 처음 매기는 행은 그대로 갱신된다")
    void sameOrFirstRunUpdates() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 14, 19, 10, 0);
        assertThat(users.updateTier(USER, CustomerTier.GOLD, 3, 300_000, at)).isEqualTo(1);
        assertThat(users.updateTier(USER, CustomerTier.VIP, 4, 600_000, at)).isEqualTo(1);
    }
}
