package com.project.delivery_service.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 배송지 변경 이력.
 *
 * <p><b>왜 이력을 남기나.</b> 주소 변경은 분쟁의 단골 소재다 — "나는 A로 바꿨는데 B로 갔다",
 * "언제 바꿨는데 왜 반영이 안 됐나". 현재 주소만 들고 있으면 그 질문에 답할 수 없다.
 * 누가 언제 무엇을 무엇으로 바꿨는지 남긴다.
 *
 * <p>변경 자체는 <b>발송 전에만</b> 허용된다. 집화된 뒤에는 실물이 택배사 손에 있어 우리가
 * 목적지를 바꿀 수 없고, 바꿨다고 기록하면 실물과 기록이 어긋난다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "address_change", schema = "delivery",
       indexes = @Index(name = "idx_address_change_delivery", columnList = "delivery_id, changed_at"))
public class AddressChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "address_change_id")
    private Long addressChangeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_id", nullable = false)
    private Delivery delivery;

    /** 변경한 주체. 고객 본인일 수도, 상담원일 수도 있다. */
    @Column(name = "changed_by", nullable = false)
    private Long changedBy;

    @Column(name = "previous_address", nullable = false, length = 600)
    private String previousAddress;

    @Column(name = "new_address", nullable = false, length = 600)
    private String newAddress;

    @Column(name = "reason", length = 200)
    private String reason;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    public AddressChange(Delivery delivery, Long changedBy,
                         String previousAddress, String newAddress, String reason) {
        this.delivery = delivery;
        this.changedBy = changedBy;
        this.previousAddress = previousAddress;
        this.newAddress = newAddress;
        this.reason = reason;
        this.changedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
