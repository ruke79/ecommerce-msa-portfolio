package com.project.chat_service.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/** 채팅 참여 (방, 회원) 유니크(V9) — 이미 생긴 중복을 지운 뒤 유니크를 거는지, 실제 PostgreSQL 에서. */
@Tag("containers")
@Testcontainers
@DisplayName("채팅 참여 유니크 (V9, PostgreSQL)")
class ParticipantUniqueMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("chatdb")
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
    @DisplayName("같은 (방, 회원) 참여 중 가장 먼저 생긴 행만 남기고 유니크를 건다")
    void removesDuplicatesThenAddsUniqueIndex() throws SQLException {
        flyway("8").migrate();
        try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            exec(db, "insert into chat.member (member_id, nickname) values (7, 'a'), (8, 'b')");
            exec(db, "insert into chat_room (room_id, room_name, room_uid) values (1, 'r', 'uid-1')");
            exec(db, "insert into participant_chat_room (participant_id, room_id, member_id) values (10, 1, 7), (11, 1, 7), (12, 1, 7), (13, 1, 8)");

            flyway("latest").migrate();

            assertThat(longs(db, "select participant_id from participant_chat_room order by participant_id"))
                    .containsExactly(10L, 13L);
            assertThatThrownBy(() -> exec(db, "insert into participant_chat_room (room_id, member_id) values (1, 8)"))
                    .hasMessageContaining("uq_participant_room_member");
        }
    }

    private static void exec(Connection db, String sql) throws SQLException {
        try (Statement st = db.createStatement()) {
            st.execute(sql);
        }
    }

    private static java.util.List<Long> longs(Connection db, String sql) throws SQLException {
        java.util.List<Long> out = new java.util.ArrayList<>();
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getLong(1));
            }
        }
        return out;
    }
}
