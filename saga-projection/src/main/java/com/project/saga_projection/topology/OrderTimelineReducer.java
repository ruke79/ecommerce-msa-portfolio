package com.project.saga_projection.topology;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.project.common.avro.OrderStatusChanged;
import com.project.common.avro.OrderTimeline;
import com.project.common.avro.OrderTimelineEntry;

/**
 * 주문 상태 변경 이벤트를 타임라인으로 접는다(docs/76) — 순수 함수라 TopologyTestDriver 없이도 검증한다.
 *
 * <p>규칙:
 * <ul>
 *   <li><b>같은 eventId 는 한 번만.</b> 릴레이는 at-least-once 라 같은 행이 두 번 올 수 있다(커밋 직전에 죽으면 재발행).
 *       항목은 늘지 않고 {@code eventCount} 만 는다 — 그 차이가 재발행이 실제로 있었다는 기록이다.</li>
 *   <li><b>순서는 도착이 아니라 발생({@code occurredAt}).</b> 지금 상태는 가장 늦게 <i>일어난</i> 변경이다. 릴레이가 두
 *       레플리카에서 돌면 나중 변경이 먼저 도착할 수 있다 — 도착 순서를 믿으면 "배달 완료 뒤에 결제 완료" 가 된다.
 *       같은 시각이면 나중에 도착한 것이 뒤다(안정 정렬).</li>
 *   <li><b>상한 {@value #MAX_ENTRIES}.</b> 주문 하나의 상태 변경은 손에 꼽지만, 버그로 핑퐁하면 레코드가 끝없이 자란다.
 *       넘으면 가장 오래된 것부터 버린다 — 지금 상태는 지킨다.</li>
 *   <li>{@code customerId} 는 비어 있을 때만 채운다.</li>
 * </ul>
 *
 * <p>[JA] 注文状態変更イベントをタイムラインに畳み込む(docs/76)。同じ eventId は一度だけ(リレーは at-least-once)。
 * 順序は到着ではなく発生(occurredAt) — 今の状態は最も遅く<i>起きた</i>変更である。上限 MAX_ENTRIES を超えると
 * 最も古いものから捨てる。
 */
public final class OrderTimelineReducer {

    public static final int MAX_ENTRIES = 50;

    private static final Comparator<OrderTimelineEntry> BY_OCCURRED_AT = Comparator.comparing(OrderTimelineEntry::getOccurredAt);

    private OrderTimelineReducer() {
    }

    /** 입력 이벤트 하나 → 항목 하나짜리 타임라인(델타). */
    public static OrderTimeline delta(OrderStatusChanged e, Instant now) {
        OrderTimelineEntry entry = OrderTimelineEntry.newBuilder()
                .setEventId(e.getEventId())
                .setPreviousStatus(e.getPreviousStatus())
                .setStatus(e.getStatus())
                .setSource(e.getSource())
                .setOccurredAt(e.getOccurredAt())
                .build();
        return OrderTimeline.newBuilder()
                .setOrderId(e.getOrderId())
                .setCustomerId(e.getCustomerId())
                .setStatus(e.getStatus())
                .setEntries(List.of(entry))
                .setEventCount(1)
                .setUpdatedAt(now)
                .build();
    }

    public static OrderTimeline apply(OrderTimeline current, OrderTimeline delta) {
        if (current == null) {
            return OrderTimeline.newBuilder(delta).build();
        }
        OrderTimeline next = OrderTimeline.newBuilder(current).build();
        next.setEventCount(current.getEventCount() + delta.getEventCount());
        if (next.getCustomerId() == null) {
            next.setCustomerId(delta.getCustomerId());
        }
        if (delta.getUpdatedAt() != null
                && (next.getUpdatedAt() == null || delta.getUpdatedAt().isAfter(next.getUpdatedAt()))) {
            next.setUpdatedAt(delta.getUpdatedAt());
        }

        List<OrderTimelineEntry> entries = new ArrayList<>(current.getEntries());
        for (OrderTimelineEntry incoming : delta.getEntries()) {
            boolean seen = entries.stream().anyMatch(x -> x.getEventId().equals(incoming.getEventId()));
            if (!seen) {
                entries.add(incoming);
            }
        }
        entries.sort(BY_OCCURRED_AT);   // List.sort 는 안정 정렬 — 같은 시각이면 도착 순서
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
        next.setEntries(entries);
        next.setStatus(entries.isEmpty() ? current.getStatus() : entries.get(entries.size() - 1).getStatus());
        return next;
    }
}
