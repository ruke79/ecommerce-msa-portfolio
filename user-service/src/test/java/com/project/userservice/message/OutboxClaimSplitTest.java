package com.project.userservice.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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

import com.project.userservice.message.repository.jpa.OutboxEventRepository;

/**
 * 가입 이벤트 릴레이와 가입 확인 메일 릴레이는 서로의 행을 가져가지 않는다 — 두 저장소 메서드의 <b>실제 SQL</b> 을 마이그레이션한
 * PostgreSQL 에 그대로 돌린다(docs/93 ③ 2단계 · 4단계 ①).
 *
 * <p>가입 이벤트 릴레이({@code claimPending})는 실패하면 행을 FAILED 로 굳힌다(재시도 없음). 메일 행을 가져가면 그 메일은 영영 나가지
 * 않는다 — 지금까지 이 조건을 지키는 시험이 없었다(조건을 지워도 모든 시험이 통과했다).
 *
 * <p>[JA] 加入イベントのリレーと加入確認メールのリレーは互いの行を取らない — 二つのメソッドの実際の SQL をマイグレーション済みの
 *      PostgreSQL で回す。加入イベントのリレーは失敗で FAILED に固める — メール行を取るとそのメールは二度と出ない。
 */
@Tag("containers")
@Testcontainers
@DisplayName("user 아웃박스 — 가입 이벤트 · 가입 메일 릴레이가 서로의 행을 가져가지 않는다(실제 SQL)")
class OutboxClaimSplitTest {

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
        insert("e-1", "user-created", "STARTED");
        insert("m-1", RegistrationMailRelay.EVENT_TYPE, "STARTED");
        insert("e-2", "user-created", "COMPLETED");
        insert("m-2", RegistrationMailRelay.EVENT_TYPE, "COMPLETED");
        insert("r-1", RegistrationMailRelay.PASSWORD_RESET_EVENT_TYPE, "STARTED");
    }

    private static void insert(String id, String type, String status) {
        jdbc.update("INSERT INTO users.outbox_event (id, aggregate_id, aggregate_type, event_type, payload, created_at, outbox_status)"
                + " VALUES (:id, '7', 'User', :type, '{}'::jsonb, now(), :status)", Map.of("id", id, "type", type, "status", status));
    }

    private static List<String> claimedIds(String method) throws NoSuchMethodException {
        String sql = OutboxEventRepository.class.getMethod(method, int.class).getAnnotation(Query.class).value();
        return jdbc.queryForList(sql, Map.of("limit", 50)).stream().map(r -> (String) r.get("id")).toList();
    }

    @Test
    @DisplayName("🔴 가입 이벤트 릴레이는 STARTED 인 이벤트만 — 메일 행은 가져가지 않는다")
    void eventRelaySkipsMailRows() throws Exception {
        assertThat(claimedIds("claimPending")).as("가입 · 재설정 메일 행은 가져가지 않는다").containsExactly("e-1");
    }

    @Test
    @DisplayName("🔴 메일 릴레이는 STARTED 인 메일 행만")
    void mailRelayTakesOnlyMailRows() throws Exception {
        assertThat(claimedIds("claimRegistrationMail")).as("가입 확인 · 비밀번호 재설정 메일 행 — STARTED 만").containsExactlyInAnyOrder("m-1", "r-1");
    }
}
