package com.project.catalog_service.batch.config;

import org.springframework.beans.factory.annotation.Qualifier;
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

import javax.sql.DataSource;

@Configuration
@EnableJpaRepositories(basePackages = "com.project.catalog_service.repository.jpa.meta",                 
                         transactionManagerRef = "metaTransactionManager")
public class MetaDataSourceConfig {

    // 데이터소스(풀)는 CatalogDataSourcePools — yml 의 hikari.* 를 묶는 곳이 거기다
    @Bean(name = "metaTransactionManager")
    public PlatformTransactionManager metaTransactionManager(@Qualifier("metaDataSource") DataSource metaDataSource) {
        return new DataSourceTransactionManager(metaDataSource);
    }
   
}