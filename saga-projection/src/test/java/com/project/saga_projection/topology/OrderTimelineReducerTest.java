package com.project.saga_projection.topology;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.avro.OrderStatusChanged;
import com.project.common.avro.OrderTimeline;
import com.project.common.avro.OrderTimelineEntry;

/** 주문 진행 타임라인의 접기 규칙(docs/76). [JA] 注文進行タイムラインの畳み込み規則(docs/76)。 */
@DisplayName("OrderTimelineReducer · 순수 접기 규칙")
class OrderTimelineReducerTest {

    private static final Instant T0 = Instant.parse("2026-10-07T00:00:00Z");

    private static OrderStatusChanged event(String id, String from, String to, Instant at) {
        return OrderStatusChanged.newBuilder().setEventId(id).setOrderId(1L).setCustomerId(7L)
                .setPreviousStatus(from).setStatus(to).setSource("test").setOccurredAt(at).build();
    }

    private static OrderTimeline fold(OrderStatusChanged... events) {
        OrderTimeline t = null;
        for (OrderStatusChanged e : events) {
            t = OrderTimelineReducer.apply(t, OrderTimelineReducer.delta(e, T0.plusSeconds(100)));
        }
        return t;
    }

    @Test
    @DisplayName("생성 → 결제 → 배송 준비: 항목 셋, 지금 상태는 마지막")
    void foldsInOrder() {
        OrderTimeline t = fold(
                event("a", null, "NOT_PROCESSED", T0),
                event("b", "NOT_PROCESSED", "PAID", T0.plusSeconds(10)),
                event("c", "PAID", "PROCESSING", T0.plusSeconds(20)));

        assertThat(t.getStatus()).isEqualTo("PROCESSING");
        assertThat(t.getEntries()).extracting(OrderTimelineEntry::getStatus).containsExactly("NOT_PROCESSED", "PAID", "PROCESSING");
        assertThat(t.getEntries().get(0).getPreviousStatus()).isNull();
        assertThat(t.getCustomerId()).isEqualTo(7L);
        assertThat(t.getEventCount()).isEqualTo(3);
        assertThat(t.getUpdatedAt()).isEqualTo(T0.plusSeconds(100));
    }

    @Test
    @DisplayName("🔴 같은 eventId 가 다시 오면(릴레이 재발행) 항목은 늘지 않고 eventCount 만 는다")
    void duplicateEventIdIsCountedNotAdded() {
        OrderStatusChanged paid = event("b", "NOT_PROCESSED", "PAID", T0.plusSeconds(10));
        OrderTimeline t = fold(event("a", null, "NOT_PROCESSED", T0), paid, paid, paid);

        assertThat(t.getEntries()).hasSize(2);
        assertThat(t.getEventCount()).isEqualTo(4);
        assertThat(t.getStatus()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("🔴 늦게 도착한 '먼저 일어난' 변경은 지금 상태를 되돌리지 않는다 — 순서는 발생 시각")
    void lateArrivalOfEarlierChangeDoesNotWinButIsPlacedByTime() {
        OrderTimeline t = fold(
                event("c", "PAID", "PROCESSING", T0.plusSeconds(20)),      // 먼저 도착
                event("b", "NOT_PROCESSED", "PAID", T0.plusSeconds(10)));  // 나중 도착, 먼저 발생

        assertThat(t.getStatus()).isEqualTo("PROCESSING");
        assertThat(t.getEntries()).extracting(OrderTimelineEntry::getStatus).containsExactly("PAID", "PROCESSING");
    }

    @Test
    @DisplayName("같은 시각이면 나중에 도착한 것이 뒤다(안정 정렬)")
    void sameInstantKeepsArrivalOrder() {
        OrderTimeline t = fold(event("x", null, "A", T0), event("y", "A", "B", T0));
        assertThat(t.getStatus()).isEqualTo("B");
    }

    @Test
    @DisplayName("상한을 넘으면 가장 오래된 것부터 버린다 — 지금 상태는 지킨다")
    void capsEntries() {
        OrderStatusChanged[] many = IntStream.range(0, OrderTimelineReducer.MAX_ENTRIES + 5)
                .mapToObj(i -> event("e" + i, null, "S" + i, T0.plusSeconds(i)))
                .toArray(OrderStatusChanged[]::new);
        OrderTimeline t = fold(many);

        assertThat(t.getEntries()).hasSize(OrderTimelineReducer.MAX_ENTRIES);
        assertThat(t.getEntries().get(0).getEventId()).isEqualTo("e5");
        assertThat(t.getStatus()).isEqualTo("S" + (OrderTimelineReducer.MAX_ENTRIES + 4));
        assertThat(t.getEventCount()).isEqualTo(OrderTimelineReducer.MAX_ENTRIES + 5);
    }
}
