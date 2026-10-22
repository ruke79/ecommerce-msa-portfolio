package com.project.catalog_service.batch.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import jakarta.persistence.EntityManagerFactory;

import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

@Configuration
@EnableJpaRepositories(basePackages = "com.project.catalog_service.repository.jpa.data",
                         entityManagerFactoryRef = "entityManagerFactory",
                         transactionManagerRef = "batchTransactionManager")
public class DataSourceConfig {

    // 수동 생성 EMF 에 전달할 JPA 설정 (application-local.yml 의 spring.jpa.*)
    //
    // 기본값이 'validate' 인 것은 의도적이다. 스키마는 Flyway 가 소유한다(docs/20).
    // 이 값이 'update' 였을 때 Hibernate 가 운영 DB 에 예고 없이 DDL 을 던졌고,
    // 그러면서도 제약을 **지우지는 않아** V4 같은 수동 마이그레이션이 따로 필요했다.
    @Value("${spring.jpa.hibernate.ddl-auto:validate}")
    private String ddlAuto;

    @Value("${spring.jpa.show-sql:false}")
    private String showSql;

    // spring.jpa.properties.* 를 통째로 받는다. 예전에는 dialect 하나만 @Value 로 꺼내 썼는데,
    // 그러면 새 프로퍼티를 추가할 때마다 여기도 고쳐야 하고 잊으면 **조용히 무시된다** —
    // B-8(ddl-auto 미전달로 스키마 미생성)이 바로 그 실패였다. 맵째로 넘겨 그 부류를 없앤다.
    private final JpaProperties jpaProperties;

    public DataSourceConfig(JpaProperties jpaProperties) {
        this.jpaProperties = jpaProperties;
    }

    // 데이터소스(풀)는 CatalogDataSourcePools — yml 의 hikari.* 를 묶는 곳이 거기다
    @Bean(name = "entityManagerFactory")
    @Primary
    public LocalContainerEntityManagerFactoryBean dataEntityManager(@Qualifier("dataDataSource") DataSource dataDataSource) {

        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();

        em.setDataSource(dataDataSource);
        em.setPackagesToScan(new String[]{"com.project.catalog_service.model"});
        em. setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        em.setPersistenceUnitName("dataPersistenceUnit");

        // 이 EMF 는 수동 생성이라 spring.jpa.* 설정이 자동으로 전달되지 않는다.
        // 프로퍼티 맵이 주석 처리돼 있어 ddl-auto 가 EMF 에 도달하지 못했고, 그 결과
        // 빈 데이터베이스에서는 스키마가 생성되지 않아 기동 시
        // 'relation "product.category" does not exist' 로 실패했다(docs/14 B-8).
        // spring.jpa.properties.* 를 통째로 넘긴 뒤, 별도 키인 ddl-auto/show-sql 만 덧댄다.
        Map<String, Object> properties = new HashMap<>(jpaProperties.getProperties());
        properties.putIfAbsent("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        properties.put("hibernate.hbm2ddl.auto", ddlAuto);
        properties.put("hibernate.show_sql", showSql);
        em.setJpaPropertyMap(properties);

        return em;
    }

    // Batch Transaction Manager를 JpaTransactionManager로 변경
    @Bean(name = "batchTransactionManager")
    @Primary
    public PlatformTransactionManager batchTransactionManager(EntityManagerFactory dataEntityManager) {
        JpaTransactionManager transactionManager = new JpaTransactionManager();
        transactionManager.setEntityManagerFactory(dataEntityManager);
        return transactionManager;
    }
}