package com.project.cart_service.outbox.model;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

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

import org.springframework.data.domain.Persistable;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.project.cart_service.outbox.OutboxStatus;

@Entity
@Getter
@Table(name = "outbox_event", schema = "cart",
       indexes = {
        @Index(columnList = "aggregate_id, aggregate_type, event_type", name = "idx_outbox_event") }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent implements Persistable<String> {
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

    public OutboxEvent(String aggregateId, String aggregateType, String eventType, String payload) {
        this.id = UUID.randomUUID().toString();
        this.aggregateId = aggregateId;
        this.aggregateType = aggregateType;
        this.eventType = eventType;
        this.payload = payload;
        // 하드코딩된 Asia/Seoul 로 인해 다른 컬럼(JPA 감사 = JVM 기본 시계)과 9시간 어긋났다
        // (docs/14 B-21). 이 컬럼은 cart.outbox_event 의 **파티션 키**다(docs/09).
        // 나머지 컬럼과 같은 시계를 쓴다. 운영에서는 JVM·DB 를 UTC 로 맞출 것.
        this.createdAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OutboxEvent that = (OutboxEvent) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }


    // ── 왜 Persistable 을 구현하는가 ────────────────────────────────────────
    //
    // id 를 생성자에서 UUID 로 직접 배정한다.
    // SimpleJpaRepository 는 save() 때 "@Id 가 null 인가"만 보고 신규 여부를 판단하므로,
    // id 가 이미 차 있는 이 엔티티는 persist() 가 아니라 merge() 로 간다. merge 는 붙일
    // 대상을 찾으려 SELECT 를 먼저 던지고(= INSERT 마다 헛조회), 같은 id 가 이미 커밋돼
    // 있으면 PK 충돌 대신 조용히 UPDATE 를 낸다.
    //
    // 플래그가 "저장됐으면 true" 방향인 이유는 기본값 false 가 곧 "새 것"이라 Lombok 이
    // 필드 초기화를 어떻게 다루든 안전한 쪽으로 떨어지기 때문이다. 불러온 엔티티는
    // @PostLoad 가 true 로 되돌리므로 수정 후 save() 는 그대로 UPDATE 로 간다.
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
}
