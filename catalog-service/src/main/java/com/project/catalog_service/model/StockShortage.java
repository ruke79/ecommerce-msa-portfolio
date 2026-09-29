package com.project.catalog_service.model;

import java.time.LocalDateTime;

import org.springframework.data.domain.Persistable;

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

/**
 * 재고 부족 기록 — 결제된 주문의 일부를 보낼 수 없다는 사실을 order 에 알리는 아웃박스 행(V13, docs/53).
 *
 * <p>재고 차감과 <b>같은 트랜잭션</b>으로 적힌다. 발행은 {@code StockShortagePublisher} 가 주기적으로 한다 — 발행이
 * 실패해도 행이 남아 다음 주기에 다시 보낸다. 돈이 걸린 알림이라 "커밋 뒤 한 번 보내 보기" 로는 모자라다.
 */
@Entity
@Table(name = "stock_shortage", schema = "product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockShortage implements Persistable<String> {

    @Id
    @Column(name = "event_id", length = 80)
    private String eventId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    public StockShortage(String eventId, Long orderId, String payload) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.payload = payload;
        this.createdAt = LocalDateTime.now();
    }

    public void markPublished() {
        this.publishedAt = LocalDateTime.now();
    }

    // 자연 키(eventId)를 배정하므로 save() 가 merge 로 가지 않게 한다 — ProcessedEvent 와 같은 이유.
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
