package com.project.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("리밸런스 · LoggingRebalanceListener / RebalanceSettings")
class LoggingRebalanceListenerTest {

    private static MockConsumer<String, String> consumerWith(TopicPartition tp, int records) {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        consumer.assign(List.of(tp));
        consumer.updateBeginningOffsets(Map.of(tp, 0L));
        consumer.updateEndOffsets(Map.of(tp, (long) records));
        for (int i = 0; i < records; i++) {
            consumer.addRecord(new ConsumerRecord<>(tp.topic(), tp.partition(), i, "k", "v"));
        }
        return consumer;
    }

    @Test
    @DisplayName("파티션을 내려놓기 전에 처리한 오프셋을 동기 커밋한다 — 넘겨받는 멤버가 같은 배치를 다시 읽지 않도록")
    void commitsBeforeRevoke() {
        TopicPartition tp = new TopicPartition("payment", 0);
        MockConsumer<String, String> consumer = consumerWith(tp, 3);
        consumer.poll(java.time.Duration.ofMillis(10));            // 3건을 읽어 position 이 3 이 된다

        new LoggingRebalanceListener().onPartitionsRevokedBeforeCommit(consumer, List.of(tp));

        assertThat(consumer.committed(java.util.Set.of(tp)).get(tp).offset()).isEqualTo(3L);
    }

    @Test
    @DisplayName("lost 는 커밋하지 않는다 — 이미 빼앗긴 파티션에 커밋하면 예외다")
    void lostDoesNotCommit() {
        TopicPartition tp = new TopicPartition("payment", 0);
        MockConsumer<String, String> consumer = consumerWith(tp, 2);
        consumer.poll(java.time.Duration.ofMillis(10));

        assertThatCode(() -> new LoggingRebalanceListener().onPartitionsLost(consumer, List.of(tp)))
                .doesNotThrowAnyException();
        assertThat(consumer.committed(java.util.Set.of(tp)).get(tp)).isNull();
    }

    @Test
    @DisplayName("받을 때 lag 를 계산하되 계산이 실패해도 리밸런스를 막지 않는다")
    void assignedIsSafe() {
        TopicPartition tp = new TopicPartition("order", 1);
        MockConsumer<String, String> consumer = consumerWith(tp, 5);
        assertThatCode(() -> new LoggingRebalanceListener().onPartitionsAssigned(consumer, List.of(tp)))
                .doesNotThrowAnyException();
        // 아무것도 할당되지 않은 호출도 안전해야 한다(리밸런스 초기에 빈 목록이 온다).
        assertThatCode(() -> new LoggingRebalanceListener().onPartitionsAssigned(consumer, List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("RebalanceSettings: 기본은 두 단계 배포용 [cooperative-sticky, range], cooperative 로 올리면 하나만")
    void assignorsFollowTheTwoStepUpgrade() {
        assertThat(RebalanceSettings.assignors(new MockEnvironment()))
                .containsExactly("org.apache.kafka.clients.consumer.CooperativeStickyAssignor",
                        "org.apache.kafka.clients.consumer.RangeAssignor");
        assertThat(RebalanceSettings.assignors(new MockEnvironment().withProperty(RebalanceSettings.PROTOCOL_PROPERTY, "cooperative")))
                .containsExactly("org.apache.kafka.clients.consumer.CooperativeStickyAssignor");
        assertThatCode(() -> RebalanceSettings.assignors(new MockEnvironment().withProperty(RebalanceSettings.PROTOCOL_PROPERTY, "sticky")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
