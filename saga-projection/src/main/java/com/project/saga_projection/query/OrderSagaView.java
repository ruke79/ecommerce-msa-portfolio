package com.project.saga_projection.query;

import java.time.Instant;
import java.util.List;

import com.project.common.avro.OrderSagaState;
import com.project.common.avro.OrderTimeline;
import com.project.common.avro.OrderTimelineEntry;

/**
 * {@code GET /api/saga/{orderId}} 응답 — 결제 사가 상태와 주문 진행 타임라인(docs/76)을 합친 것. Avro 생성 클래스를
 * 그대로 JSON 으로 내면 {@code schema}· {@code specificData} 같은 내부 필드가 새어 나오므로 평범한 레코드로 옮긴다.
 *
 * <p>둘 중 하나만 있을 수 있다 — 사가 상태만(옛 주문, 상태 이벤트 도입 전) 또는 타임라인만(결제 요청이 아직 안 나간
 * 직후). 없는 쪽은 null · 빈 목록이다.
 *
 * @param phase       결제 사가 단계. 사가 상태가 없으면 null
 * @param orderStatus 지금 주문 상태(타임라인의 마지막). 타임라인이 없으면 null
 * @param timeline    발생 순서. 비어 있을 수 있다
 * @param stale       이 인스턴스가 활성 태스크가 아니라 대기 복제본에서 답했다 — 변경 로그만큼 늦을 수 있다
 *
 * <p>[JA] 決済サーガの状態と注文進行タイムライン(docs/76)を合わせた応答。どちらか一方だけのこともある。
 */
public record OrderSagaView(
        long orderId,
        String phase,
        Long customerId,
        String amounts,
        String couponName,
        Long paymentId,
        String paymentStatus,
        Instant requestedAt,
        Instant respondedAt,
        int requestCount,
        int responseCount,
        Instant updatedAt,
        String orderStatus,
        List<TimelineEntry> timeline,
        boolean stale) {

    /** 상태 변경 하나. eventId 는 내지 않는다 — 화면에 쓸 일이 없고 아웃박스 행 id 를 밖에 알릴 이유도 없다. */
    public record TimelineEntry(String previousStatus, String status, String source, Instant occurredAt) {
        static TimelineEntry of(OrderTimelineEntry e) {
            return new TimelineEntry(e.getPreviousStatus(), e.getStatus(), e.getSource(), e.getOccurredAt());
        }
    }

    public static OrderSagaView of(OrderSagaState s, OrderTimeline t, boolean stale) {
        if (s == null && t == null) {
            throw new IllegalArgumentException("사가 상태와 타임라인이 둘 다 없다");
        }
        long orderId = s != null ? s.getOrderId() : t.getOrderId();
        Long customerId = s != null && s.getCustomerId() != null ? s.getCustomerId() : (t == null ? null : t.getCustomerId());
        Instant updatedAt = latest(s == null ? null : s.getUpdatedAt(), t == null ? null : t.getUpdatedAt());
        return new OrderSagaView(
                orderId,
                s == null ? null : s.getPhase().name(),
                customerId,
                s == null ? null : s.getAmounts(),
                s == null ? null : s.getCouponName(),
                s == null ? null : s.getPaymentId(),
                s == null ? null : s.getPaymentStatus(),
                s == null ? null : s.getRequestedAt(),
                s == null ? null : s.getRespondedAt(),
                s == null ? 0 : s.getRequestCount(),
                s == null ? 0 : s.getResponseCount(),
                updatedAt,
                t == null ? null : t.getStatus(),
                t == null ? List.of() : t.getEntries().stream().map(TimelineEntry::of).toList(),
                stale);
    }

    /** 예전 시그니처 — 사가 상태만. */
    public static OrderSagaView of(OrderSagaState s, boolean stale) {
        return of(s, null, stale);
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }
}
