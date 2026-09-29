package com.project.order_service.outbox.model;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import com.project.common.outbox.OutboxStatus;


@Entity
@Setter
@Getter
@Table(name = "payment_outbox_event", schema = "orders",
       indexes = {
        @Index(columnList = "aggregate_id, aggregate_type, event_type", name = "idx_payment_outbox_event") }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentOutboxEvent implements Persistable<String> {
    @Id
    private String id;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    //@Column(name = "payload", nullable = false, columnDefinition = "json")
    @Column(name = "payload", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Enumerated(EnumType.STRING)
    private OutboxStatus outboxStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * 행을 쓴 순간의 추적 문맥(전파 헤더). 릴레이가 이것으로 스팬을 되살린 채 보내 요청 → 사가가
     * 하나의 추적으로 이어진다(docs/51 §4e, OutboxTraceContext). 없으면 예전처럼 보낸다.
     */
    @Column(name = "trace_context", length = 512)
    private String traceContext;

    public PaymentOutboxEvent(String aggregateId, String aggregateType, String eventType, String payload, OutboxStatus status) {
        this.id = UUID.randomUUID().toString();
        this.aggregateId = aggregateId;
        this.aggregateType = aggregateType;
        this.eventType = eventType;
        this.payload = payload;
        this.outboxStatus = status;
        // 하드코딩된 Asia/Seoul 때문에 같은 사건인데도 orders 테이블(JPA 감사 = JVM 기본 시계)과
        // 이 컬럼이 9시간 어긋났다(docs/14 B-21). 이 컬럼은 payment_outbox_event 의
        // **파티션 키**이므로(docs/09) 월 경계에서 이벤트가 잘못된 파티션에 들어간다.
        // 나머지 컬럼과 같은 시계를 쓴다. 운영에서는 JVM·DB 를 UTC 로 맞출 것.
        this.createdAt = LocalDateTime.now();
    }

    // ── 왜 Persistable 을 구현하는가 ────────────────────────────────────────
    //
    // id 를 생성자에서 UUID 로 **직접 배정**한다. 그래서 save() 시점에 id 가 이미 null 이
    // 아니고, SimpleJpaRepository 는 그것만 보고 "기존 행"으로 판단해 persist() 가 아니라
    // merge() 를 고른다. merge 는 붙일 대상을 찾으려고 SELECT 를 먼저 던진다 —
    // **새 아웃박스 이벤트를 넣을 때마다 반드시 빈손으로 끝나는 SELECT 가 한 번씩** 나갔다.
    // 아웃박스는 주문 한 건마다 쓰이는 경로라 그대로 곱해진다.
    //
    // 더 나쁜 쪽은 성능이 아니다. merge 는 같은 id 가 이미 커밋돼 있으면 PK 충돌 대신
    // **UPDATE** 로 조용히 넘어간다. 삽입이 실패해 줄 것이라고 기대하는 코드가 있다면
    // 그 기대가 성립하지 않는다.
    //
    // 플래그는 "새 것이면 true" 가 아니라 "저장됐으면 true" 방향이다. 기본값 false 가 곧
    // "새 것"이라, Lombok 빌더나 @NoArgsConstructor 가 필드 초기화를 어떻게 다루든
    // 안전한 쪽으로 떨어진다. 불러온 엔티티는 @PostLoad 가 true 로 되돌리므로
    // 스케줄러의 상태 변경(setOutboxStatus 후 save)은 그대로 UPDATE 로 간다.
    @Transient
    private boolean persisted;

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostPersist
    @PostLoad
    private void markPersisted() {
        this.persisted = true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PaymentOutboxEvent that = (PaymentOutboxEvent) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

}
