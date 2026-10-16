package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.project.userservice.model.Review;
import com.project.userservice.repository.jpa.ReviewRepository;

import jakarta.persistence.EntityManagerFactory;

/**
 * 상품 리뷰 목록 — 리뷰 수와 상관없이 쿼리 하나(실제 PostgreSQL).
 *
 * <p>🐛 예전: 파생 쿼리 뒤에 리뷰마다 LAZY 인 작성자 · 스타일을 따로 읽어, 리뷰 300건짜리 상품 상세 한 번에 쿼리 약 600개였다.
 *
 * <p>[JA] 商品レビュー一覧 — レビュー数に関係なくクエリ一つ。以前は 300 件で約 600 クエリだった。
 */
@Tag("containers")
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("리뷰 목록 — N+1 없음(실제 PostgreSQL)")
class ReviewQueryCountTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("userdb").withUsername("product").withPassword("testpw");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
        registry.add("eureka.client.enabled", () -> false);
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.project.userservice.model")
    @EnableJpaRepositories(basePackageClasses = ReviewRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = ReviewRepository.class))
    static class JpaOnly {
    }

    private static final long PRODUCT = 9_100L;
    private static final int REVIEWS = 30;

    @Autowired
    ReviewRepository reviews;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManagerFactory emf;

    @BeforeEach
    void seed() {
        for (int i = 0; i < REVIEWS; i++) {
            long uid = 9_200_000_000L + i;
            jdbc.update("INSERT INTO users.users (user_id, username, email, account_non_expired, account_non_locked, "
                    + "credentials_non_expired, email_verified, enabled, is_two_factor_enabled) "
                    + "VALUES (?, ?, ?, true, true, true, true, true, false)", uid, "rv" + i, "rv" + i + "@example.com");
            jdbc.update("INSERT INTO users.review_style (rstyle_id, color, image) VALUES (?, 'black', 'img')", 9_300L + i);
            jdbc.update("INSERT INTO users.review (review_id, rating, product_id, rstyle_id, user_id, review) "
                    + "VALUES (?, 4, ?, ?, ?, 'ok')", 9_400L + i, PRODUCT, 9_300L + i, uid);
        }
    }

    @Test
    @DisplayName("🔴 리뷰 30건의 작성자 · 스타일을 읽어도 쿼리는 1개 — 리뷰마다 늘지 않는다")
    void oneQueryForAllReviews() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        List<Review> found = reviews.findByProductId(PRODUCT);
        // 화면 변환(ReviewService.toDto)이 만지는 것들
        found.forEach(r -> {
            r.getReviewedBy().getUsername();
            r.getReviewedBy().getImage();
            r.getStyle().getColor();
        });

        assertThat(found).hasSize(REVIEWS);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }
}
