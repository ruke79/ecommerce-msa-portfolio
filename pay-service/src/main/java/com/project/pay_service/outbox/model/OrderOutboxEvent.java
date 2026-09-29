package com.project.pay_service.outbox.model;

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

import com.project.common.constants.PaymentStatus;
import com.project.common.outbox.OutboxStatus;


@Entity
@Setter
@Getter
@Table(name = "order_outbox_event", schema = "pay",
       indexes = {
        @Index(columnList = "aggregate_id, aggregate_type, event_type", name = "idx_order_outbox_event") }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderOutboxEvent implements Persistable<String> {
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

    public OrderOutboxEvent(String aggregateId, String aggregateType, String eventType, String payload, OutboxStatus status) {
        this.id = UUID.randomUUID().toString();
        this.aggregateId = aggregateId;
        this.aggregateType = aggregateType;
        this.eventType = eventType;
        this.payload = payload;
        this.outboxStatus = status;
        // 하드코딩된 Asia/Seoul 때문에 같은 사건인데도 pay.payment.created_at(JPA 감사 =
        // JVM 기본 시계)과 이 컬럼이 9시간 어긋났다(docs/14 B-21). 이 컬럼은
        // order_outbox_event 의 **파티션 키**이므로(docs/09), 월말 9시간 동안 생성된 이벤트가
        // 다음 달 파티션으로 들어가 보존/DETACH 작업이 엉뚱한 행을 건드리게 된다.
        // 나머지 컬럼과 같은 시계를 쓴다. 운영에서는 JVM·DB 를 UTC 로 맞출 것.
        this.createdAt = LocalDateTime.now();
    }

    // ── 왜 Persistable 을 구현하는가 ────────────────────────────────────────
    //
    // id 를 생성자에서 UUID 로 **직접 배정**하므로 save() 시점에 id 가 null 이 아니다.
    // SimpleJpaRepository 는 그것만 보고 "기존 행"으로 판단해 persist() 대신 merge() 를
    // 고르고, merge 는 붙일 대상을 찾으려 SELECT 를 먼저 던진다 — **새 아웃박스 이벤트마다
    // 반드시 빈손으로 끝나는 SELECT 가 한 번씩** 나갔다. 결제 한 건마다 곱해지는 경로다.
    //
    // 성능보다 나쁜 쪽: merge 는 같은 id 가 이미 커밋돼 있으면 PK 충돌 대신 **UPDATE** 로
    // 조용히 넘어간다. 삽입 실패를 기대하는 코드가 있다면 그 기대가 성립하지 않는다.
    //
    // 플래그가 "저장됐으면 true" 방향인 이유는 기본값 false 가 곧 "새 것"이라 Lombok 이
    // 필드 초기화를 어떻게 다루든 안전한 쪽으로 떨어지기 때문이다. 불러온 엔티티는
    // @PostLoad 가 true 로 되돌리므로 스케줄러의 상태 변경은 그대로 UPDATE 로 간다.
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
        OrderOutboxEvent that = (OrderOutboxEvent) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

}
