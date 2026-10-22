package com.project.catalog_service.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * catalog 의 Flyway 이력은 처음부터 끝까지 product 스키마 하나 — 빈 DB 에서 두 번째 기동이 V1 부터 다시 돌지 않는다(실제 PostgreSQL ·
 * Spring Boot 의 Flyway 자동 설정 · 서비스의 yml 그대로).
 *
 * <p>🐛 2026-10-21(운영 백업으로 마이그레이션을 미리 올려 보다 드러남): 운영처럼 DB 를 소유한 계정이 {@code product} 이고 서비스의 스키마도
 * {@code product} 다. Flyway 는 스키마를 정하지 않으면 접속의 {@code current_schema()} — 기본 search_path {@code "$user", public} 의 첫
 * <b>있는</b> 스키마 — 를 쓴다. 빈 DB 의 첫 기동에는 product 스키마가 없어 이력을 public 에 쓰고, 마이그레이션이 product 를 만든 뒤의
 * 기동부터는 product 에서 이력을 찾지 못해 기준선(0)을 다시 잡고 V1 부터 모두 다시 돌렸다 — V10 의 첫 화면 구성이 두 벌이 됐다(10 → 20).
 * 운영 productdb 에도 이력 표가 public(V5) · product(V18) 둘 남아 있다.
 *
 * <p>[JA] catalog の Flyway 履歴は最初から product スキーマ一つ — 空の DB で 2 回目の起動が V1 から再実行しない。DB の所有ロールと
 *      サービスのスキーマが同じ名前のため、既定の current_schema() が product スキーマの作成で変わっていた。
 */
@Tag("containers")
@Testcontainers
@DisplayName("catalog Flyway 이력 스키마 — 빈 DB 에서 두 번 기동해도 마이그레이션은 한 번 (PostgreSQL)")
class FlywayHistorySchemaTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("productdb")
                    .withUsername("product")
                    .withPassword("testpw");

    /** 서비스와 같은 설정 — catalog 의 application.yml 과 공통 application-common.yml 의 spring.flyway.* 를 그대로 읽는다. */
    private static ApplicationContextRunner start() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
                .withBean(DataSource.class, () -> DataSourceBuilder.create().url(postgres.getJdbcUrl())
                        .username(postgres.getUsername()).password(postgres.getPassword()).build())
                .withInitializer(ctx -> {
                    try {
                        for (String file : List.of("application.yml", "application-common.yml")) {
                            for (PropertySource<?> ps : new YamlPropertySourceLoader().load(file, new ClassPathResource(file))) {
                                ctx.getEnvironment().getPropertySources().addLast(ps);
                            }
                        }
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
    }

    private static List<String> rows(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    @Test
    @DisplayName("🔴 두 번째 기동은 아무것도 다시 돌리지 않는다 — 이력 표는 product 하나 · 첫 화면 구성은 한 벌(예전: public · product 둘, 10 → 20)")
    void secondStartDoesNotReapplyMigrations() throws SQLException {
        start().run(ctx -> assertThat(ctx).hasNotFailed());
        String homeSections = rows("select count(*) from content.home_section").get(0);

        start().run(ctx -> assertThat(ctx).hasNotFailed());   // 파드가 다시 뜬다

        assertThat(rows("select count(*) from content.home_section").get(0)).as("V10 의 첫 화면 구성이 두 벌이 되지 않는다")
                .isEqualTo(homeSections);
        assertThat(rows("select table_schema from information_schema.tables where table_name = 'flyway_schema_history' order by 1"))
                .as("이력 표는 하나 — 빈 DB 의 첫 기동부터").containsExactly("product");
        assertThat(rows("select max(version::int)::text from product.flyway_schema_history where success and version ~ '^[0-9]+$'").get(0))
                .as("마지막 마이그레이션까지").isNotEqualTo("0");
    }
}
