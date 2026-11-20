package com.project.catalog_service.batch.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.zaxxer.hikari.HikariDataSource;

/**
 * catalog 의 두 커넥션 풀 — {@code spring.datasource-data}(productdb) · {@code spring.datasource-meta}(metadb).
 *
 * <p>🐛 2026-10-19: 예전에는 {@code DataSourceProperties.initializeDataSourceBuilder().build()} 만 해서 URL · 계정만 읽고
 * yml 의 {@code hikari.*} 는 <b>하나도 적용되지 않았다</b> — 풀 최대 5(`DB_POOL_SIZE`) · 최소 2 · 대기 5초 · 누수 감지 20초 대신
 * Hikari 기본값(최대 10 · 최소 = 최대 · 대기 30초 · 누수 감지 없음)으로 돌았다. spike 부하 측정의 파드별 지표
 * {@code hikaricp_connections_max} = 10 으로 드러났고(docs/83 §5), 그 전의 '풀 5 → 10' 실험은 풀을 바꾸지 못했다.
 * 빈에 {@code @ConfigurationProperties("….hikari")} 를 붙여 만든 풀에 그 설정을 묶는다(Spring Boot 의 여러 데이터소스 방식).
 *
 * <p>JPA 설정(DataSourceConfig · MetaDataSourceConfig)과 떼어 둔 것은 풀만 실제 yml 로 띄워 보기 위해서다
 * ({@code CatalogDataSourcePoolsTest}).
 *
 * <p>[JA] catalog の二つのコネクションプール — {@code spring.datasource-data}(productdb) · {@code spring.datasource-meta}(metadb)。
 *
 * <p>[JA] 2026-10-19: 以前は {@code DataSourceProperties.initializeDataSourceBuilder().build()} だけで URL · アカウントしか読まず、
 * yml の {@code hikari.*} は<b>一つも適用されていなかった</b> — プール最大 5(`DB_POOL_SIZE`) · 最小 2 · 待ち 5 秒 · リーク検知 20 秒の代わりに
 * Hikari の既定値(最大 10 · 最小 = 最大 · 待ち 30 秒 · リーク検知なし)で動いていた。spike 負荷測定の Pod 別指標
 * {@code hikaricp_connections_max} = 10 で判明し(docs/83 §5)、それ以前の「プール 5 → 10」実験はプールを変えられていなかった。
 * Bean に {@code @ConfigurationProperties("….hikari")} を付けて、作ったプールにその設定を結び付ける(Spring Boot の複数データソース方式)。
 *
 * <p>[JA] JPA 設定(DataSourceConfig · MetaDataSourceConfig)と分けてあるのは、プールだけを実際の yml で起動して確かめるためである
 * ({@code CatalogDataSourcePoolsTest})。
 */
@Configuration
public class CatalogDataSourcePools {

    @Bean(name = "dataDataSourceProperties")
    @ConfigurationProperties(prefix = "spring.datasource-data")
    public DataSourceProperties dataDataSourceProperties() {
        return new DataSourceProperties();
    }

    /** 앱 데이터(JPA) — productdb. */
    // [JA] アプリデータ(JPA) — productdb。
    @Bean(name = "dataDataSource")
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource-data.hikari")
    public HikariDataSource dataDataSource() {
        return dataDataSourceProperties().initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean(name = "metaDataSourceProperties")
    @ConfigurationProperties(prefix = "spring.datasource-meta")
    public DataSourceProperties metaDataSourceProperties() {
        return new DataSourceProperties();
    }

    /** metadb — 이름과 달리 Spring Batch 메타데이터용이 아니다(application-local.yml 의 datasource-meta 주석). */
    // [JA] metadb — 名前と違って Spring Batch メタデータ用ではない(application-local.yml の datasource-meta のコメント)。
    @Bean(name = "metaDataSource")
    @ConfigurationProperties(prefix = "spring.datasource-meta.hikari")
    public HikariDataSource metaDataSource() {
        return metaDataSourceProperties().initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }
}
