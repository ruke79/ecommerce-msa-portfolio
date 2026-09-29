package com.project.catalog_service.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.springframework.data.domain.Persistable;

/**
 * 컨슈머 멱등성 처리 이력 (docs/11 §4 G8).
 *
 * Kafka 는 at-least-once 이므로 같은 이벤트가 재전달될 수 있고, 사가 재실행으로 같은 주문의
 * 재고 차감 요청이 두 번 올 수도 있다. 처리한 eventId 를 비즈니스 변경과 **같은 DB 트랜잭션**으로
 * 기록해 두면, 재전달 시 이 테이블 조회만으로 중복을 스킵할 수 있다(재고 이중 차감 방지).
 * 스키마는 Flyway V2__create_processed_event.sql 이 소유한다.
 */
@Entity
@Table(name = "processed_event", schema = "product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent implements Persistable<String> {

    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    public ProcessedEvent(String eventId) {
        this.eventId = eventId;
        this.processedAt = LocalDateTime.now();
    }

    // ── 왜 Persistable 을 구현하는가 ────────────────────────────────────────
    //
    // eventId 는 Kafka 가 준 값을 **그대로 배정**하는 자연 키다. 그래서 save() 시점에 id 가
    // 이미 null 이 아니고, SimpleJpaRepository 는 그것만 보고 persist() 가 아니라 merge() 를
    // 고른다. 결과가 둘이다.
    //
    //  1. merge 는 붙일 대상을 찾으려 SELECT 를 먼저 던진다 — 이벤트를 처리할 때마다 반드시
    //     빈손으로 끝나는 SELECT 가 한 번씩 더 나갔다. 바로 앞 줄에서 existsById 로 이미
    //     같은 행을 확인한 뒤라 완전히 같은 조회가 두 번 나간 셈이다.
    //
    //  2. 더 중요한 쪽. 리스너 주석은 "동시 중복은 PK 충돌로 전체 롤백된다"고 적고 있는데,
    //     merge 는 다른 트랜잭션이 이미 커밋한 같은 eventId 를 SELECT 로 찾아내면 충돌
    //     대신 **UPDATE** 를 낸다. 예외가 없으니 롤백도 없고, 재고 차감은 두 번 반영된다.
    //     existsById 를 동시에 통과한 두 소비자를 막아 주는 마지막 방어선이 그 경로에서만
    //     조용히 빠져 있었다. persist() 로 가야 PK 충돌이 실제로 나고 주석대로 동작한다.
    @Transient
    private boolean persisted;

    @Override
    public String getId() {
        return eventId;
    }

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
