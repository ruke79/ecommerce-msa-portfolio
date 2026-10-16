package com.project.notification_service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * notification-service 기동 검증 — 실제 PostgreSQL/Redis 컨테이너 위에서.
 *
 * <p>다른 서비스들과 같은 이유다: 이 저장소에서 가장 비싼 버그들은 하나같이 <b>기동해야만</b>
 * 드러나는 종류였다(docs/14). 특히 이 서비스는 스키마를 Flyway 가 소유하고 Hibernate 는
 * {@code validate} 만 하므로, <b>기동에 성공한다는 것 자체가</b> 마이그레이션과 엔티티가
 * 일치한다는 증명이다(docs/20).
 */
@Tag("containers")
@Testcontainers
@SpringBootTest
@DisplayName("notification-service 기동 (Testcontainers)")
class NotificationServiceApplicationTests {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("notificationdb")
                    .withUsername("product")
                    .withPassword("testpw");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", "testpw");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "testpw");
        registry.add("spring.data.redis.ssl.enabled", () -> false);
        registry.add("eureka.client.enabled", () -> false);
        registry.add("eureka.client.register-with-eureka", () -> false);
        registry.add("eureka.client.fetch-registry", () -> false);
        registry.add("management.tracing.enabled", () -> false);
        registry.add("server.ssl.enabled", () -> false);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private com.project.notification_service.controller.NotificationController notificationController;

    @Autowired
    private com.project.notification_service.repository.NotificationPreferenceRepository preferenceRepository;

    @Test
    @DisplayName("🔴 알림 설정 변경이 DB 에 저장된다 — 예전엔 트랜잭션 밖 세터라 200 · 새 값을 주고도 저장되지 않았다")
    void preferenceUpdateIsPersisted() {
        long userId = 9_001L;
        var response = notificationController.updatePreferences(userId, "pref@example.com",
                java.util.Map.of("smsEnabled", true, "emailEnabled", false));
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        var saved = preferenceRepository.findByUserId(userId).orElseThrow();
        assertThat(saved.isSmsEnabled()).as("smsEnabled").isTrue();
        assertThat(saved.isEmailEnabled()).as("emailEnabled").isFalse();

        // 인증된 번호 없이 2단계 인증 켜기 → 409, 함께 보낸 다른 변경도 저장하지 않는다
        var rejected = notificationController.updatePreferences(userId, "pref@example.com",
                java.util.Map.of("twoFactorEnabled", true, "emailEnabled", true));
        assertThat(rejected.getStatusCode().value()).isEqualTo(409);
        assertThat(preferenceRepository.findByUserId(userId).orElseThrow().isEmailEnabled()).isFalse();
    }

    @Test
    @DisplayName("애플리케이션 컨텍스트가 실제로 뜬다")
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    @DisplayName("Flyway 가 notification 스키마의 테이블을 만든다 (validate 통과의 증거)")
    void schemaIsCreatedByFlyway() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "select table_name from information_schema.tables where table_schema = 'notification'")) {

            java.util.List<String> tables = new java.util.ArrayList<>();
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
            assertThat(tables)
                    .as("Hibernate 는 validate 만 하므로, 이 테이블들은 Flyway 가 만든 것이다")
                    .contains("notification", "phone_verification", "notification_preference",
                              // V3 가 추가한 메일 아웃박스 (docs/29)
                              "mail_message");
        }
    }

    @Test
    @DisplayName("메일 채널 컬럼이 엔티티와 일치한다 — 기동 성공이 곧 validate 통과다")
    void mailChannelColumnsMatchEntities() throws Exception {
        // 이 테스트가 도는 시점에 이미 컨텍스트가 떴다는 것은 ddl-auto=validate 를 통과했다는
        // 뜻이므로, 여기서는 V3 가 실제로 무엇을 만들었는지를 눈으로 확인하는 의미가 크다.
        assertThat(columnsOf("mail_message"))
                .contains("mail_id", "to_email", "recipient_user_id", "kind", "params",
                          "locale", "status", "attempts", "detail", "source_event_id",
                          "created_at", "completed_at");

        assertThat(columnsOf("notification_preference"))
                .as("메일 채널이 추가한 두 컬럼")
                .contains("email", "email_enabled");
    }

    private java.util.List<String> columnsOf(String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "select column_name from information_schema.columns"
                     + " where table_schema = 'notification' and table_name = '" + table + "'")) {

            java.util.List<String> columns = new java.util.ArrayList<>();
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
            return columns;
        }
    }

    @Autowired
    private com.project.notification_service.service.VerificationService verificationService;

    @Autowired
    private com.project.notification_service.service.MailDispatcher mailDispatcher;

    @Autowired
    private org.springframework.scheduling.config.ScheduledTaskHolder scheduledTasks;

    private static java.sql.Timestamp utc(java.time.Duration offset) {
        return java.sql.Timestamp.valueOf(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plus(offset));
    }

    @Test
    @DisplayName("🔴 만료 인증번호 · 오래된 끝난 메일은 매일 지운다 — 대기 중인 메일 · 최근 행은 남는다")
    void retentionJobsDeleteOnlyWhatTheyShould() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            try (var ps = c.prepareStatement("insert into notification.phone_verification "
                    + "(attempts, consumed, created_at, expires_at, user_id, phone_e164, code_hash) values (0, false, ?, ?, null, ?, 'h')")) {
                ps.setTimestamp(1, utc(java.time.Duration.ofDays(-3)));
                ps.setTimestamp(2, utc(java.time.Duration.ofDays(-3)));
                ps.setString(3, "+821000000001");
                ps.executeUpdate();
                ps.setTimestamp(1, utc(java.time.Duration.ZERO));
                ps.setTimestamp(2, utc(java.time.Duration.ofMinutes(5)));
                ps.setString(3, "+821000000002");
                ps.executeUpdate();
            }
            try (var ps = c.prepareStatement("insert into notification.mail_message "
                    + "(to_email, kind, status, attempts, source_event_id, created_at) values ('a@example.com', 'ORDER_PLACED', ?, 1, ?, ?)")) {
                Object[][] rows = {{"SENT", "purge-sent-old", -400}, {"FAILED", "purge-failed-old", -400},
                        {"PENDING", "purge-pending-old", -400}, {"SENT", "purge-sent-recent", -10}};
                for (Object[] r : rows) {
                    ps.setString(1, (String) r[0]);
                    ps.setString(2, (String) r[1]);
                    ps.setTimestamp(3, utc(java.time.Duration.ofDays((Integer) r[2])));
                    ps.executeUpdate();
                }
            }
        }

        verificationService.purgeExpired();
        mailDispatcher.purgeFinished();

        assertThat(strings("select phone_e164 from notification.phone_verification where phone_e164 like '+82100000000%'"))
                .containsExactly("+821000000002");
        assertThat(strings("select source_event_id from notification.mail_message where source_event_id like 'purge-%' order by 1"))
                .containsExactly("purge-pending-old", "purge-sent-recent");
        assertThat(scheduledTasks.getScheduledTasks().stream().map(t -> t.getTask().toString()))
                .as("@Scheduled 가 없으면 아무도 부르지 않는다(예전 purgeExpired)")
                .anyMatch(t -> t.contains("VerificationService.purgeExpired"))
                .anyMatch(t -> t.contains("MailDispatcher.purgeFinished"));
    }

    @Test
    @DisplayName("🔴 같은 인증번호를 동시에 틀려도 시도는 5회까지만 — 잠그고 읽는다")
    void concurrentWrongCodesCannotExceedTheAttemptLimit() throws Exception {
        String phone = "+821077770000";
        try (Connection c = dataSource.getConnection();
             var ps = c.prepareStatement("insert into notification.phone_verification "
                     + "(attempts, consumed, created_at, expires_at, user_id, phone_e164, code_hash) values (0, false, ?, ?, null, ?, 'x')")) {
            ps.setTimestamp(1, utc(java.time.Duration.ZERO));
            ps.setTimestamp(2, utc(java.time.Duration.ofMinutes(5)));
            ps.setString(3, phone);
            ps.executeUpdate();
        }

        int threads = 12;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var start = new java.util.concurrent.CountDownLatch(1);
        var mismatches = new java.util.concurrent.atomic.AtomicInteger();
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                var r = verificationService.verify("01077770000",
                        com.project.notification_service.sms.PhoneNumbers.Region.KR, "000000", null);
                if (r instanceof com.project.notification_service.service.VerificationService.VerifyResult.Rejected rej
                        && rej.reason().contains("일치하지")) {
                    mismatches.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (var f : futures) {
            f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(mismatches.get()).as("코드 비교까지 간 시도 수").isEqualTo(5);
        assertThat(strings("select attempts from notification.phone_verification where phone_e164 = '" + phone + "'"))
                .containsExactly("5");
    }

    private java.util.List<String> strings(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            java.util.List<String> out = new java.util.ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        }
    }
}
