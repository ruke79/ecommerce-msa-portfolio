package com.project.delivery_service.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.FetchType;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 택배사 스캔 이벤트 1건 — 고객이 보는 타임라인의 한 줄.
 *
 * <p><b>원문을 함께 보존한다.</b> 표준 상태로 매핑해 저장하되 택배사가 준 문자열과 원본
 * 응답을 남긴다. 매핑이 틀렸을 때 원문이 없으면 "왜 이 상태로 판정됐는지"를 재구성할 수
 * 없고, 택배사가 상태 문구를 바꿨을 때도 알아챌 방법이 없다(docs/25 N-5).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "delivery_event", schema = "delivery",
       indexes = @Index(name = "idx_delivery_event_timeline", columnList = "shipment_id, occurred_at"))
public class DeliveryEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    /** 이벤트는 <b>상자</b>에 붙는다 — 분할 배송이면 상자마다 이력이 따로 흐른다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private DeliveryStatus status;

    /** 택배사가 알려준 스캔 시각. 우리가 조회한 시각이 아니다 — 둘은 몇 시간 차이 날 수 있다. */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** '대전허브', '서울강남' 같은 지점명. 국내 택배가 주는 '위치'는 이것이 전부다. */
    @Column(name = "location", length = 120)
    private String location;

    @Column(name = "description", length = 300)
    private String description;

    @Column(name = "raw_status", length = 120)
    private String rawStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload")
    private String rawPayload;

    /**
     * 중복 제거 키.
     *
     * <p>폴링은 매번 <b>전체 이력</b>을 돌려준다. 이 키가 없으면 같은 스캔이 조회 횟수만큼
     * 쌓여 타임라인이 중복으로 뒤덮인다. 택배사가 이벤트에 안정적인 ID 를 주지 않는 경우가
     * 많아 (시각 + 원문상태 + 지점)의 해시를 쓴다.
     *
     * <p>완벽하지는 않다 — 같은 지점에서 같은 시각에 두 번 스캔되면 하나로 합쳐진다.
     * 그 손실보다 중복이 타임라인을 뒤덮는 쪽이 훨씬 나쁘다는 판단이다.
     */
    @Column(name = "dedup_key", nullable = false, length = 120)
    private String dedupKey;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public DeliveryEvent(Shipment shipment, DeliveryStatus status, LocalDateTime occurredAt,
                         String location, String description, String rawStatus, String rawPayload) {
        this.shipment = shipment;
        this.status = status;
        this.occurredAt = occurredAt;
        this.location = location;
        this.description = description;
        this.rawStatus = rawStatus;
        this.rawPayload = rawPayload;
        this.dedupKey = dedupKey(occurredAt, rawStatus, location);
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public static String dedupKey(LocalDateTime occurredAt, String rawStatus, String location) {
        String seed = (occurredAt == null ? "" : occurredAt.toString())
                + "|" + (rawStatus == null ? "" : rawStatus)
                + "|" + (location == null ? "" : location);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(seed.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 40);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다", e);
        }
    }
}
