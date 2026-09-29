package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Debezium 아웃박스 커넥터가 <b>스케줄러 릴레이와 같은 토픽·키 규칙</b>을 쓰는지 지키는 가드레일.
 *
 * <p>두 릴레이는 같은 아웃박스 행을 같은 소비자에게 보낸다. 규칙이 어긋나면 어느 쪽을 켜느냐에
 * 따라 이벤트가 다른 토픽(아무도 안 듣는 {@code Order.events})이나 다른 파티션(다른 키 바이트)에
 * 앉는다 — docs/38 §5 A1·A2, §1.15. 규칙은 이렇다.
 * <ul>
 *   <li>토픽: {@code event_type} 으로 정하고 스케줄러의 이름과 같게 —
 *       {@code payment-request→order}, {@code token-update→token-u}, {@code payment-response→payment}.</li>
 *   <li>키: {@code aggregate_id}(집합체 id) 그대로, {@code StringConverter} 로 — 앱 프로듀서의
 *       {@code StringSerializer} 와 바이트가 같아야 같은 파티션이다.</li>
 *   <li>테이블: 실제 스키마({@code orders.}/{@code pay.}) — {@code public.} 은 아무것도 캡처하지 않는다.</li>
 * </ul>
 */
@DisplayName("CDC 가드레일 · 커넥터가 스케줄러 릴레이와 같은 토픽·키 규칙인가")
class CdcConnectorGuardrailTest {

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    /** 스케줄러가 쓰는 event_type → 토픽. PaymentOutboxScheduler / OrderOutboxScheduler 와 같아야 한다. */
    private static final Map<String, String> SCHEDULER_TOPICS = Map.of(
            "payment-request", "order",
            "token-update", "token-u",
            "payment-response", "payment");

    private static final Set<String> CONNECTORS = Set.of(
            "order-outbox-connector-postgresql.json", "order-outbox-connector-mysql.json",
            "payment-outbox-connector-postgresql.json", "payment-outbox-connector-mysql.json");

    @Test
    @DisplayName("네 커넥터 모두: event_type 라우팅, 스케줄러와 같은 토픽 이름, aggregate_id 키, StringConverter")
    void connectorsMatchSchedulerRelay() throws IOException {
        ObjectMapper om = new ObjectMapper();
        List<String> offenders = new ArrayList<>();

        for (String file : CONNECTORS) {
            Path p = repoRoot().resolve(file);
            assertThat(p).as(file).exists();
            JsonNode c = om.readTree(Files.readString(p)).get("config");

            if (!"event_type".equals(text(c, "transforms.outbox.route.by.field"))) {
                offenders.add(file + " → route.by.field 가 event_type 이 아니다");
            }
            if (!"aggregate_id".equals(text(c, "transforms.outbox.table.field.event.key"))) {
                offenders.add(file + " → 키가 aggregate_id 가 아니다");
            }
            if (!"org.apache.kafka.connect.storage.StringConverter".equals(text(c, "key.converter"))) {
                offenders.add(file + " → key.converter 가 StringConverter 가 아니다 (JsonConverter 는 키 바이트를 감싸 파티션이 달라진다)");
            }

            // RegexRouter 하나하나가 스케줄러 표의 한 줄이어야 한다.
            String[] chain = text(c, "transforms").split(",");
            int routes = 0;
            for (String t : chain) {
                if (t.equals("outbox")) continue;
                String regex = text(c, "transforms." + t + ".regex");
                String target = text(c, "transforms." + t + ".replacement");
                String eventType = regex.replaceAll("[\\^$]", "");
                routes++;
                if (!target.equals(SCHEDULER_TOPICS.get(eventType))) {
                    offenders.add(file + " → " + eventType + " 이 " + target + " 로 간다. 스케줄러는 " + SCHEDULER_TOPICS.get(eventType));
                }
            }
            if (routes == 0) {
                offenders.add(file + " → RegexRouter 가 없다 (${event_type} 그대로 나가면 아무도 안 듣는다)");
            }

            String table = text(c, "table.include.list");
            if (table.startsWith("public.")) {
                offenders.add(file + " → table.include.list 가 public. 이다 — 실제 스키마는 orders./pay.");
            }
        }

        assertThat(offenders).isEmpty();
    }

    private static String text(JsonNode c, String key) {
        JsonNode n = c.get(key);
        return n == null ? "" : n.asText();
    }
}
