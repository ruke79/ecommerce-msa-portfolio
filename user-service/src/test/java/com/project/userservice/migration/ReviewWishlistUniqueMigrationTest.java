package com.project.userservice.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * V18 — 리뷰 · 위시리스트 중복 정리 + 유니크(실제 PostgreSQL, V17 까지 올린 뒤 옛 모양의 중복을 심고 적용).
 *
 * <p>[JA] V18 — レビュー · ウィッシュリストの重複整理 + ユニーク(実 PostgreSQL)。
 */
@Tag("containers")
@Testcontainers
@DisplayName("리뷰 · 위시리스트 중복 정리와 유니크 (V18, PostgreSQL)")
class ReviewWishlistUniqueMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("userdb").withUsername("product").withPassword("testpw");

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
    @DisplayName("🔴 같은 사람 · 같은 상품의 리뷰는 최근 하나, 같은 찜은 먼저 것 하나 — 그 뒤 중복은 DB 가 막는다")
    void dedupesThenEnforces() throws SQLException {
        flyway("17").migrate();
        try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            for (long uid : new long[] {1, 2}) {
                exec(db, "INSERT INTO users.users (user_id, username, email, account_non_expired, account_non_locked, "
                        + "credentials_non_expired, email_verified, enabled, is_two_factor_enabled) VALUES (" + uid + ", 'u" + uid
                        + "', 'u" + uid + "@example.com', true, true, true, true, true, false)");
            }
            // 사용자 1 이 상품 100 에 리뷰 둘(더블클릭) — 옛 것(10)에 좋아요, 새 것(11)이 남아야 한다
            review(db, 10, 1, 100, 310);
            review(db, 11, 1, 100, 311);
            review(db, 20, 2, 100, 320);   // 다른 사람 — 그대로
            exec(db, "INSERT INTO users.review_like (like_id, review_id, user_id) VALUES (900, 10, 2)");
            // 찜: 사용자 1 · 상품 100 · black 둘, style NULL 둘, 사용자 2 는 하나
            wish(db, 50, 1, 100, "'black'");
            wish(db, 51, 1, 100, "'black'");
            wish(db, 52, 1, 100, "NULL");
            wish(db, 53, 1, 100, "NULL");
            wish(db, 60, 2, 100, "'black'");

            flyway("latest").migrate();

            assertThat(longs(db, "SELECT review_id FROM users.review ORDER BY review_id")).containsExactly(11L, 20L);
            assertThat(longs(db, "SELECT rstyle_id FROM users.review_style ORDER BY rstyle_id"))
                    .as("지운 리뷰만 쓰던 스타일도 지운다").containsExactly(311L, 320L);
            assertThat(longs(db, "SELECT count(*) FROM users.review_like")).as("지운 리뷰의 좋아요는 FK 로 함께").containsExactly(0L);
            assertThat(longs(db, "SELECT wish_item_id FROM users.wishlist ORDER BY wish_item_id")).containsExactly(50L, 52L, 60L);

            assertThatThrownBy(() -> review(db, 12, 1, 100, 312)).hasMessageContaining("uq_review_user_product");
            assertThatThrownBy(() -> wish(db, 54, 1, 100, "NULL")).hasMessageContaining("uq_wishlist_user_product_style");
        }
    }

    private static void review(Connection db, long id, long uid, long product, long style) throws SQLException {
        exec(db, "INSERT INTO users.review_style (rstyle_id, color, image) VALUES (" + style + ", 'black', 'img')");
        exec(db, "INSERT INTO users.review (review_id, rating, product_id, rstyle_id, user_id, review) VALUES ("
                + id + ", 4, " + product + ", " + style + ", " + uid + ", 'ok')");
    }

    private static void wish(Connection db, long id, long uid, long product, String style) throws SQLException {
        exec(db, "INSERT INTO users.wishlist (wish_item_id, user_id, product_id, style) VALUES (" + id + ", " + uid + ", "
                + product + ", " + style + ")");
    }

    private static void exec(Connection db, String sql) throws SQLException {
        try (Statement st = db.createStatement()) {
            st.execute(sql);
        }
    }

    private static List<Long> longs(Connection db, String sql) throws SQLException {
        List<Long> out = new ArrayList<>();
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getLong(1));
            }
        }
        return out;
    }
}
