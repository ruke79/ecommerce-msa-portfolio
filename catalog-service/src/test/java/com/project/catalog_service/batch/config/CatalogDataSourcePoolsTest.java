package com.project.catalog_service.batch.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import com.zaxxer.hikari.HikariDataSource;

/**
 * catalog 의 두 커넥션 풀이 yml 의 {@code hikari.*} 를 그대로 쓴다 — 실제 {@code application-local.yml} 로 띄운다.
 *
 * <p>🐛 2026-10-19: 예전 빈은 URL · 계정만 읽어 풀이 Hikari 기본값(최대 10 · 대기 30초 · 누수 감지 없음)으로 돌았다(docs/83 §5).
 * 풀은 만들기만 하고 연결하지 않는다(Hikari 는 첫 getConnection 에 연다) — DB 없이 돈다.
 *
 * <p>[JA] catalog の二つのプールが yml の hikari.* をそのまま使う — 本物の application-local.yml で起動して確かめる。
 */
@DisplayName("catalog 커넥션 풀 — yml 의 hikari.* 가 적용된다")
class CatalogDataSourcePoolsTest {

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withInitializer(ctx -> {
                    try {
                        for (PropertySource<?> ps : new YamlPropertySourceLoader()
                                .load("application-local.yml", new ClassPathResource("application-local.yml"))) {
                            ctx.getEnvironment().getPropertySources().addLast(ps);
                        }
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .withPropertyValues("LOCAL_DB_PASSWORD=x")
                .withUserConfiguration(CatalogDataSourcePools.class);
    }

    @Test
    @DisplayName("🔴 기본값 — 최대 5 · 최소 2 · 대기 5초 · 누수 감지 20초 · 수명 20분 · 풀 이름(예전: 10 · 10 · 30초 · 없음)")
    void bindsHikariSettingsFromYaml() {
        runner().run(ctx -> {
            HikariDataSource data = ctx.getBean("dataDataSource", HikariDataSource.class);
            HikariDataSource meta = ctx.getBean("metaDataSource", HikariDataSource.class);
            assertThat(data.getJdbcUrl()).contains("/productdb");
            assertThat(meta.getJdbcUrl()).contains("/metadb");
            for (HikariDataSource ds : new HikariDataSource[] { data, meta }) {
                assertThat(ds.getMaximumPoolSize()).as(ds.getJdbcUrl()).isEqualTo(5);
                assertThat(ds.getMinimumIdle()).isEqualTo(2);
                assertThat(ds.getConnectionTimeout()).isEqualTo(5_000);
                assertThat(ds.getLeakDetectionThreshold()).isEqualTo(20_000);
                assertThat(ds.getMaxLifetime()).isEqualTo(1_200_000);
            }
            assertThat(data.getPoolName()).isEqualTo("catalog-data");
            assertThat(meta.getPoolName()).isEqualTo("catalog-meta");
        });
    }

    @Test
    @DisplayName("🔴 DB_POOL_SIZE 가 두 풀에 먹힌다 — ops/53 pool load 가 실제로 풀을 바꾼다")
    void poolSizeVariableApplies() {
        runner().withPropertyValues("DB_POOL_SIZE=8").run(ctx -> {
            assertThat(ctx.getBean("dataDataSource", HikariDataSource.class).getMaximumPoolSize()).isEqualTo(8);
            assertThat(ctx.getBean("metaDataSource", HikariDataSource.class).getMaximumPoolSize()).isEqualTo(8);
        });
    }
}
