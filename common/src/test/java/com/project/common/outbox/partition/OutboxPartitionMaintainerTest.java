package com.project.common.outbox.partition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("아웃박스 파티션 잡 — 설정 검증")
class OutboxPartitionMaintainerTest {

    @Test
    @DisplayName("파티션 이름은 V1 마이그레이션과 같은 <테이블>_YYYY_MM")
    void partitionName() {
        assertThat(OutboxPartitionMaintainer.partitionName("payment_outbox_event", YearMonth.of(2027, 2)))
                .isEqualTo("payment_outbox_event_2027_02");
    }

    @Test
    @DisplayName("🔴 테이블 이름은 '스키마.테이블' 소문자만 — 식별자를 SQL 에 이어 붙이므로")
    void rejectsUnsafeIdentifiers() {
        for (String bad : List.of("payment_outbox_event", "orders.x; DROP TABLE y", "Orders.Outbox", "orders.\"x\"")) {
            assertThatThrownBy(() -> new OutboxPartitionMaintainer(null, List.of(bad), 3, Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> new OutboxPartitionMaintainer(null, List.of("orders.t"), -1, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }
}
