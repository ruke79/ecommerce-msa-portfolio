package com.project.notification_service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.project.notification_service.domain.Notification;
import com.project.notification_service.domain.NotificationType;
import com.project.notification_service.repository.NotificationRepository;
import com.project.notification_service.service.NotificationService;

/**
 * '모두 읽음' — 안 읽은 알림이 500건을 넘어도 모두 읽음이 된다(실제 PostgreSQL, docs/93 ③ 3단계).
 *
 * <p>🐛 2026-11-21 까지 최신 500건(읽은 것 포함)만 읽어 하나씩 고쳤다 — 알림이 많은 사용자는 오래된 안 읽은 알림이 남아 배지가 0 이
 * 되지 않았다. 이제 UPDATE 한 문장.
 *
 * <p>[JA] 「すべて既読」— 未読が 500 件を超えてもすべて既読になる(実 PostgreSQL)。以前は最新 500 件(既読を含む)だけを直していた。
 */
@Tag("containers")
@Testcontainers
@SpringBootTest(properties = {
        // 스케줄러가 시험 중 DB 를 건드리지 않게. [JA] スケジューラーが試験中に DB に触れないように。
        "notification.mail.dispatch-initial-delay-ms=600000",
        "notification.sms.dispatch-initial-delay-ms=600000",
        "notification.mail.scrub-initial-delay-ms=600000"
})
@DisplayName("'모두 읽음' — 500건을 넘어도 모두(실제 PostgreSQL)")
class NotificationMarkAllReadTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("notificationdb").withUsername("product").withPassword("testpw");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379).withCommand("redis-server", "--requirepass", "testpw");

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

    @Autowired NotificationService service;
    @Autowired NotificationRepository repository;
    @Autowired JdbcTemplate jdbc;

    private static Notification note(long recipient, int i) {
        return Notification.builder().recipientId(recipient).type(NotificationType.ORDER_PLACED)
                .title("t" + i).content("c" + i).url("order/" + i).sourceEventId("mark-all-" + recipient + "-" + i).build();
    }

    @Test
    @DisplayName("🔴 안 읽은 알림 600건(가장 오래된 것 포함)이 모두 읽음 — 다른 사람 · 지운 알림은 그대로")
    void marksEveryUnreadNotification() {
        long me = 71L;
        long other = 72L;
        List<Notification> rows = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            rows.add(note(me, i));
        }
        for (int i = 0; i < 3; i++) {
            rows.add(note(other, i));
        }
        repository.saveAll(rows);
        // 가장 오래된 것 하나는 지운 알림 — 건드리지 않는다. [JA] 最も古い一つは削除済み — 触らない。
        jdbc.update("UPDATE notification.notification SET deleted = true WHERE source_event_id = 'mark-all-71-0'");

        int changed = service.markAllRead(me);

        assertThat(changed).as("지운 것 하나를 뺀 599건").isEqualTo(599);
        assertThat(repository.countByRecipientIdAndReadFalseAndDeletedFalse(me)).as("배지가 0").isZero();
        assertThat(repository.countByRecipientIdAndReadFalseAndDeletedFalse(other)).as("다른 사람은 그대로").isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT read_status FROM notification.notification WHERE source_event_id = 'mark-all-71-0'",
                Boolean.class)).as("지운 알림은 건드리지 않는다").isFalse();
    }
}
