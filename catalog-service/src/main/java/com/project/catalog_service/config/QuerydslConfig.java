package com.project.catalog_service.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
public class QuerydslConfig {

    // 1. catalogPersistenceUnit를 위한 EntityManager 주입
    // DataSourceConfig에 정의된 "catalogPersistenceUnit"과 연결됩니다.
    @PersistenceContext(unitName = "dataPersistenceUnit")
    //@PersistenceContext
    //@Qualifier("entityManagerFactory")
    private EntityManager entityManagerFactory;

    /**
     * catalogEntityManager를 사용하는 JPAQueryFactory 빈을 등록합니다.
     * 이 빈은 메인 데이터베이스에 대한 쿼리를 생성할 때 사용됩니다.
     * 
     * @return JPAQueryFactory 빈
     */
    @Bean
    public JPAQueryFactory catalogJpaQueryFactory() {

        return new JPAQueryFactory(this.entityManagerFactory);
    }
    
}