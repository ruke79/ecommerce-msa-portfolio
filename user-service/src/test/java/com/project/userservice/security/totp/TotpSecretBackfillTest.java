package com.project.userservice.security.totp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 기동 백필 — 평문 · 옛 키 암호문을 지금 키로 맞춘다(docs/70). 실제 SQL(H2)로.
 *
 * <p>JPA 로 하면 변환기가 이미 평문으로 풀어 줘서 "바뀐 것이 없다" 로 보이고 저장되지 않는다 — 그래서 백필은 JDBC 다.
 */
@DisplayName("2FA 비밀 — 기동 백필")
class TotpSecretBackfillTest {

    private static final String JWT = "user-test-secret-user-test-secret-0123456";
    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:totp" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create schema users");
        jdbc.execute("create table users.users (user_id bigint primary key, two_factor_secret varchar(255))");
    }

    private String stored(long id) {
        return jdbc.queryForObject("select two_factor_secret from users.users where user_id = ?", String.class, id);
    }

    @Test
    @DisplayName("🔴 평문은 암호화하고, 파생 키 암호문은 전용 키로 다시 잠그고, 이미 맞는 것 · 없는 것은 그대로")
    void migratesEverythingToTheCurrentKey() {
        TotpSecretCipher derivedOnly = new TotpSecretCipher("", JWT);
        TotpSecretCipher withKey = new TotpSecretCipher(KEY, JWT);
        String current = withKey.encrypt("CURRENTSECRET");
        jdbc.update("insert into users.users values (1, 'PLAINSECRET'), (2, ?), (3, ?), (4, null)",
                derivedOnly.encrypt("DERIVEDSECRET"), current);

        int changed = new TotpSecretBackfill(jdbc, withKey).migrate();

        assertThat(changed).isEqualTo(2);
        assertThat(stored(1)).startsWith("enc:k1:").doesNotContain("PLAINSECRET");
        assertThat(withKey.decrypt(stored(1))).isEqualTo("PLAINSECRET");
        assertThat(stored(2)).startsWith("enc:k1:");
        assertThat(withKey.decrypt(stored(2))).isEqualTo("DERIVEDSECRET");
        assertThat(stored(3)).as("이미 지금 키 — 다시 쓰지 않는다").isEqualTo(current);
        assertThat(stored(4)).isNull();
    }

    @Test
    @DisplayName("두 번 돌려도 같다(멱등) — 레플리카가 동시에 기동해도 해가 없다")
    void idempotent() {
        TotpSecretCipher withKey = new TotpSecretCipher(KEY, JWT);
        jdbc.update("insert into users.users values (1, 'PLAINSECRET')");
        TotpSecretBackfill backfill = new TotpSecretBackfill(jdbc, withKey);

        assertThat(backfill.migrate()).isEqualTo(1);
        String once = stored(1);
        assertThat(backfill.migrate()).isZero();
        assertThat(stored(1)).isEqualTo(once);
    }

    @Test
    @DisplayName("풀 수 없는 값은 건드리지 않는다 — 기동을 멈추지 않고, 남은 행은 계속 처리한다")
    void undecryptableRowIsLeftAlone() {
        TotpSecretCipher withKey = new TotpSecretCipher(KEY, JWT);
        String foreign = new TotpSecretCipher("", "another-jwt-secret-another-jwt-secret-01").encrypt("X");
        jdbc.update("insert into users.users values (1, ?), (2, 'PLAINSECRET')", foreign);

        assertThat(new TotpSecretBackfill(jdbc, withKey).migrate()).isEqualTo(1);
        assertThat(stored(1)).isEqualTo(foreign);
    }
}
