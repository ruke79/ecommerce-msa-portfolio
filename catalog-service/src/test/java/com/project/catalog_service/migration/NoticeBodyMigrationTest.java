package com.project.catalog_service.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 공지 본문 — 대형 객체(OID)에 있던 글을 text 로 옮기고 대형 객체를 해제하는지(V18), 실제 PostgreSQL 에서. */
@Tag("containers")
@Testcontainers
@DisplayName("공지 본문 대형 객체 → text (V18, PostgreSQL)")
class NoticeBodyMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("productdb")
                    .withUsername("product")
                    .withPassword("testpw");

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .target(target)
                .load();
    }

    @Test
    @DisplayName("OID 번호가 든 본문은 글로 바뀌고, 옮긴 것 · 버려진 대형 객체는 해제된다. 원래 글인 본문은 그대로")
    void movesBodiesOutOfLargeObjects() throws SQLException {
        flyway("17").migrate();
        try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            // @Lob 시절의 저장 모양: 글은 대형 객체에, 열에는 그 OID. 수정 때 버려진 옛 대형 객체도 하나.
            long lo = scalar(db, "select lo_from_bytea(0, convert_to('점검 안내 — 결제가 잠시 멈춥니다.', 'UTF8'))");
            scalar(db, "select lo_from_bytea(0, convert_to('고치기 전의 본문', 'UTF8'))");
            exec(db, "insert into content.notice (notice_id, title, body) values (1, 't', '" + lo + "')");
            // 숫자로만 된 글이지만 그런 대형 객체가 없는 본문 — 손대지 않는다
            exec(db, "insert into content.notice (notice_id, title, body) values (2, 't', '2026')");
            exec(db, "insert into content.notice (notice_id, title, body) values (3, 't', '평범한 글')");

            flyway("latest").migrate();

            assertThat(text(db, "select body from content.notice where notice_id = 1")).isEqualTo("점검 안내 — 결제가 잠시 멈춥니다.");
            assertThat(text(db, "select body from content.notice where notice_id = 2")).isEqualTo("2026");
            assertThat(text(db, "select body from content.notice where notice_id = 3")).isEqualTo("평범한 글");
            assertThat(scalar(db, "select count(*) from pg_largeobject_metadata")).as("대형 객체가 남지 않는다").isZero();
        }
    }

    private static void exec(Connection db, String sql) throws SQLException {
        try (Statement st = db.createStatement()) {
            st.execute(sql);
        }
    }

    private static long scalar(Connection db, String sql) throws SQLException {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String text(Connection db, String sql) throws SQLException {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
