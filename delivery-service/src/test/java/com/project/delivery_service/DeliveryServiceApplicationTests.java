package com.project.delivery_service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * delivery-service 기동 검증.
 *
 * <p>스키마는 Flyway 가 소유하고 Hibernate 는 {@code validate} 만 하므로, <b>기동에
 * 성공한다는 것 자체가</b> 마이그레이션과 엔티티가 일치한다는 증명이다(docs/20).
 */
@Tag("containers")
@Testcontainers
@SpringBootTest
@DisplayName("delivery-service 기동 (Testcontainers)")
class DeliveryServiceApplicationTests {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("deliverydb")
                    .withUsername("product")
                    .withPassword("testpw");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("eureka.client.enabled", () -> false);
        registry.add("eureka.client.register-with-eureka", () -> false);
        registry.add("eureka.client.fetch-registry", () -> false);
        registry.add("management.tracing.enabled", () -> false);
        registry.add("server.ssl.enabled", () -> false);
        // 폴링이 배경에서 돌면 테스트가 불안정해진다.
        registry.add("delivery.polling.interval-ms", () -> 3_600_000);
        registry.add("delivery.polling.initial-delay-ms", () -> 3_600_000);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("애플리케이션 컨텍스트가 실제로 뜬다")
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    @DisplayName("Flyway 가 delivery 스키마의 테이블을 만든다 (validate 통과의 증거)")
    void schemaIsCreatedByFlyway() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "select table_name from information_schema.tables where table_schema = 'delivery'")) {
            List<String> tables = new ArrayList<>();
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
            assertThat(tables)
                    .as("Hibernate 는 validate 만 하므로, 이 테이블들은 Flyway 가 만든 것이다")
                    .contains("delivery", "shipment", "shipment_item", "delivery_event",
                              "carrier", "address_change");
        }
    }
}
