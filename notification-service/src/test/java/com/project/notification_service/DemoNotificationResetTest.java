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
import com.project.notification_service.controller.DemoResetController;
import com.project.notification_service.repository.BroadcastReadRepository;

/**
 * '모두 읽음' — 안 읽은 알림이 500건을 넘어도 모두 읽음이 된다(실제 PostgreSQL, docs/93 ③ 3단계).
 *
 * <p>🐛 2026-11-21 까지 최신 500건(읽은 것 포함)만 읽어 하나씩 고쳤다 — 알림이 많은 사용자는 오래된 안 읽은 알림이 남아 배지가 0 이
 * 되지 않았다. 이제 UPDATE 한 문장.
 *
 * <p>[JA] 「すべて既読」— 未読が 500 件を超えてもすべて既読になる(実 PostgreSQL)。以前は最新 500 件(既読を含む)だけを直していた。
 */
/**
 * 공개 데모 계정의 알림 초기화(docs/100) — 실제 PostgreSQL 에서 그 계정들의 알림만 지운다. 부팅 자체가 두 삭제 질의(JPQL)를 검증한다.
 * [JA] 公開デモアカウントの通知初期化 — 実際の PostgreSQL でそのアカウントの通知だけを消す。起動自体が二つの削除クエリを検証する。
 */
@Tag("containers")
@Testcontainers
@SpringBootTest(properties = {
        "notification.mail.dispatch-initial-delay-ms=600000",
        "notification.sms.dispatch-initial-delay-ms=600000",
        "notification.mail.scrub-initial-delay-ms=600000"
})
@DisplayName("데모 알림 초기화 — 그 계정들의 알림만(실제 PostgreSQL)")
class DemoNotificationResetTest {

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

    @Autowired DemoResetController controller;
    @Autowired NotificationRepository repository;
    @Autowired BroadcastReadRepository broadcastReads;

    private static Notification note(long recipient, int i) {
        return Notification.builder().recipientId(recipient).type(NotificationType.ORDER_PLACED)
                .title("t" + i).content("c" + i).url("order/" + i).sourceEventId("demo-reset-" + recipient + "-" + i).build();
    }

    @Test
    @DisplayName("🔴 데모 계정 둘의 알림은 모두 지우고(지운 표시 포함) 다른 사람 것은 그대로")
    void deletesOnlyDemoRecipients() {
        List<Notification> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rows.add(note(81L, i));
            rows.add(note(82L, i));
            rows.add(note(83L, i));
        }
        repository.saveAll(rows);

        var res = controller.reset(new DemoResetController.DemoResetRequest(List.of(81L, 82L)), null, null, null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).containsEntry("notifications", 6);
        assertThat(repository.countByRecipientIdAndReadFalseAndDeletedFalse(81L)).isZero();
        assertThat(repository.countByRecipientIdAndReadFalseAndDeletedFalse(83L)).as("다른 사람").isEqualTo(3);
    }

    @Test
    @DisplayName("🔴 게이트웨이를 거친 요청(신원 헤더 있음)은 관리자라도 403 · 지우지 않는다")
    void gatewayCallsAreRejected() {
        repository.save(note(84L, 0));
        var res = controller.reset(new DemoResetController.DemoResetRequest(List.of(84L)), "ROLE_ADMIN", "a@example.com", "1");
        assertThat(res.getStatusCode().value()).isEqualTo(403);
        assertThat(repository.countByRecipientIdAndReadFalseAndDeletedFalse(84L)).isEqualTo(1);
    }

    @Test
    @DisplayName("계정이 50 을 넘으면 400 · 빈 목록이면 아무것도 하지 않는다")
    void limits() {
        List<Long> many = new ArrayList<>();
        for (long i = 0; i < 51; i++) {
            many.add(9000 + i);
        }
        assertThat(controller.reset(new DemoResetController.DemoResetRequest(many), null, null, null).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.reset(new DemoResetController.DemoResetRequest(List.of()), null, null, null).getBody())
                .containsEntry("notifications", 0);
    }
}
