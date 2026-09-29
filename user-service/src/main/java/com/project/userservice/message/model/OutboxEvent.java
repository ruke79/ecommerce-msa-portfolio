package com.project.userservice.message.model;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;
import com.project.userservice.message.OutboxStatus;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@Table(name = "outbox_event", schema="users", 
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

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * 아직 보냈는가.
     *
     * <p>🐛 이 컬럼은 <b>없었다</b>(V1 주석: "user-service 의 OutboxEvent 엔티티에는
     * outbox_status 컬럼이 없다"). CDC 릴레이만 상정했기 때문인데, 정작 user-created 용
     * 커넥터는 만들어진 적이 없고 발행은 {@code kafkaTemplate.send} 한 줄이었다 — 즉
     * 아웃박스 테이블은 <b>있는데 아무도 쓰지 않았다.</b>
     *
     * <p>이제 애플리케이션 폴러가 이 값을 보고 고른다({@code UserOutboxScheduler}).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "outbox_status", nullable = false, length = 20)
    private OutboxStatus outboxStatus;

    /**
     * 행을 쓴 순간의 추적 문맥(전파 헤더). 릴레이가 이것으로 스팬을 되살린 채 보내 요청 → 사가가
     * 하나의 추적으로 이어진다(docs/51 §4e, OutboxTraceContext). 없으면 예전처럼 보낸다.
     */
    @Column(name = "trace_context", length = 512)
    private String traceContext;

    public OutboxEvent(String aggregateId, String aggregateType, String eventType, String payload) {
        this.id = UUID.randomUUID().toString();
        this.aggregateId = aggregateId;
        this.aggregateType = aggregateType;
        this.eventType = eventType;
        this.payload = payload;
        this.outboxStatus = OutboxStatus.STARTED;
        // 하드코딩된 Asia/Seoul 로 인해 다른 컬럼(JPA 감사 = JVM 기본 시계)과 9시간 어긋났다
        // (docs/14 B-21). 이 컬럼은 users.outbox_event 의 **파티션 키**다(docs/09).
        // 나머지 컬럼과 같은 시계를 쓴다. 운영에서는 JVM·DB 를 UTC 로 맞출 것.
        this.createdAt = LocalDateTime.now();
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
    /** 쓰는 순간의 추적 문맥을 붙인다(docs/51 §4e). */
    public OutboxEvent withTraceContext(String traceContext) {
        this.traceContext = traceContext;
        return this;
    }

    /** 발행에 성공했다. 다시는 고르지 않는다. */
    public void markCompleted() {
        this.outboxStatus = OutboxStatus.COMPLETED;
    }

    /**
     * 발행에 실패했다.
     *
     * <p>지우지 않고 남긴다 — <b>무엇이 안 나갔는지 알 수 있어야</b> 손으로든 배치로든
     * 복구할 수 있다. 조용히 사라지는 것이 이 전체 사태의 원인이었다.
     */
    public void markFailed() {
        this.outboxStatus = OutboxStatus.FAILED;
    }

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
