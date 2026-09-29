package com.project.catalog_service.batch.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
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

    @Bean(name = "metaDataSourceProperties")    
    @ConfigurationProperties(prefix = "spring.datasource-meta")
    public DataSourceProperties metaDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "metaDataSource")    
    public DataSource metaDataSource() {
        return metaDataSourceProperties().initializeDataSourceBuilder().build();
    }

    @Bean(name = "metaTransactionManager")    
    public PlatformTransactionManager metaTransactionManager() {
        return new DataSourceTransactionManager(metaDataSource());        
    }
   
}