package com.project.userservice.security.passkey;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.project.userservice.repository.jpa.PasskeyCredentialRepository;

/**
 * 패스키 서명 카운터는 줄지 않는다 — {@link PasskeyCredentialRepository#recordLogin} 의 <b>실제 SQL</b> 을 마이그레이션한 PostgreSQL 에
 * 그대로 돌린다(docs/93 ③ 3단계).
 *
 * <p>🐛 2026-11-21 까지 엔티티에 새 카운터를 넣고 통째로 save 했다 — 같은 패스키로 동시에 로그인하면 늦게 저장한 쪽의 더 작은 값이
 * 덮어써 카운터가 줄 수 있었고, 그러면 '카운터가 줄면 복제' 판정이 그 값 위로 다시 통과했다.
 *
 * <p>[JA] パスキーの署名カウンターは減らない — recordLogin の実際の SQL をマイグレーション済みの PostgreSQL でそのまま回す。以前は
 *      エンティティごと save しており、同時ログインで遅い方の小さい値が上書きしてカウンターが減り得た。
 */
@Tag("containers")
@Testcontainers
@DisplayName("패스키 서명 카운터 — 늦게 온 작은 값이 줄이지 않는다(실제 SQL)")
class PasskeySignCountMonotonicTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("userdb").withUsername("product").withPassword("testpw");

    static NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("0").load().migrate();
        jdbc = new NamedParameterJdbcTemplate(
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    /** 저장소 메서드에 붙은 SQL 그대로. [JA] リポジトリのメソッドに付いた SQL そのまま。 */
    private static String recordLoginSql() throws NoSuchMethodException {
        return PasskeyCredentialRepository.class.getMethod("recordLogin", Long.class, long.class, boolean.class, LocalDateTime.class)
                .getAnnotation(Query.class).value();
    }

    private static void recordLogin(long passkeyId, long signCount) throws NoSuchMethodException {
        jdbc.update(recordLoginSql(), Map.of("passkeyId", passkeyId, "signCount", signCount, "backupState", true,
                "now", LocalDateTime.now()));
    }

    @Test
    @DisplayName("🔴 7 을 기록한 뒤 늦게 온 5 는 카운터를 줄이지 않는다 — 더 큰 값은 올린다 · 마지막 사용 시각은 남는다")
    void signCountNeverDecreases() throws Exception {
        jdbc.update("INSERT INTO users.passkey_credential (passkey_id, user_id, credential_id, user_handle, attested_credential_data, "
                + "sign_count, name, created_at) VALUES (501, 7, 'cred-501', 'h', '\\x00'::bytea, 3, 'k', now())", Map.of());

        recordLogin(501, 7);
        recordLogin(501, 5);   // 동시 로그인의 늦은 쪽 — 더 작은 값 [JA] 同時ログインの遅い方 — 小さい値
        assertThat(jdbc.queryForObject("SELECT sign_count FROM users.passkey_credential WHERE passkey_id = 501", Map.of(), Long.class))
                .isEqualTo(7);

        recordLogin(501, 9);
        assertThat(jdbc.queryForObject("SELECT sign_count FROM users.passkey_credential WHERE passkey_id = 501", Map.of(), Long.class))
                .isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT last_used_at IS NOT NULL FROM users.passkey_credential WHERE passkey_id = 501",
                Map.of(), Boolean.class)).isTrue();
    }
}
