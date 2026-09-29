package com.project.delivery_service.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 배송 상자 1개 — <b>추적의 단위</b>.
 *
 * <p>이 엔티티가 존재하는 이유가 <b>분할 배송</b>이다. 한 주문의 상품이 서로 다른 창고에
 * 있거나 부피가 커서 나뉘어 나가면, 송장번호도 택배사도 도착일도 상자마다 다르다.
 * 추적 정보를 주문에 직접 붙이면 이 경우를 표현할 수 없다 — 아마존이 주문 하나를
 * "패키지 1/2", "패키지 2/2" 로 나눠 보여주는 이유다.
 *
 * <p>그래서 계층이 이렇게 된다:
 * <pre>
 *   Delivery (주문 1건의 배송 전체)
 *     └── Shipment[] (상자 = 송장번호 = 추적 단위)
 *           ├── ShipmentItem[] (이 상자에 무엇이 들었나)
 *           └── DeliveryEvent[] (택배사 스캔 이력)
 * </pre>
 *
 * <p>상자가 하나뿐인 흔한 경우에도 Shipment 는 하나 생긴다. 특수 케이스를 따로 두지 않는
 * 편이 화면·로직 양쪽에서 단순하다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "shipment", schema = "delivery",
       uniqueConstraints = {
           // 같은 택배사 안에서 송장번호는 유일하다. 오타로 남의 배송을 덮어쓰는 것을 막는다.
           @UniqueConstraint(name = "uk_shipment_tracking",
                             columnNames = {"carrier_code", "tracking_number"})
       },
       indexes = {
           // 폴링 대상 조회. 없으면 배송이 쌓일수록 스케줄러가 전체 스캔을 한다.
           @Index(name = "idx_shipment_poll", columnList = "next_poll_at"),
           @Index(name = "idx_shipment_delivery", columnList = "delivery_id")
       })
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "shipment_id")
    private Long shipmentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_id", nullable = false)
    private Delivery delivery;

    /** 화면의 "패키지 1/2". 1부터 시작한다. */
    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(name = "carrier_code", length = 30)
    private String carrierCode;

    @Column(name = "tracking_number", length = 60)
    private String trackingNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private DeliveryStatus status;

    /** 택배사가 알려준 예상 도착일. 상자마다 다를 수 있다. */
    @Column(name = "estimated_at")
    private LocalDateTime estimatedAt;

    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    // ── 국제 배송 ────────────────────────────────────────────────────────────

    /**
     * 국제 배송인가. 목적지 국가만으로 판단하지 않는 이유는, 같은 국가 안에서도
     * 보세구역 경유 등 통관이 끼는 경우가 있고 택배사 서비스가 국내/국제로 나뉘기 때문이다.
     */
    @Column(name = "international", nullable = false)
    private boolean international;

    /**
     * 개인통관고유부호. 한국으로 들어오는 개인 수입 통관에 <b>법적으로 필요</b>하다.
     *
     * <p>⚠️ 개인정보다. 응답에 그대로 실으면 안 되고(마스킹해서 내려준다), 통관이 끝나면
     * 보관할 이유가 없다. 여기 두는 것은 통관 진행 중 재제출 요구에 대응하기 위해서다.
     */
    @Column(name = "customs_clearance_no", length = 40)
    private String customsClearanceNo;

    /** 통관 신고번호. 사용자가 관세청에서 직접 조회할 때 쓴다. */
    @Column(name = "customs_declaration_no", length = 40)
    private String customsDeclarationNo;

    // ── 폴링 제어 ────────────────────────────────────────────────────────────

    /** 다음 조회 예정 시각. null 이면 조회하지 않는다(송장 없음 또는 종료된 배송). */
    @Column(name = "next_poll_at")
    private LocalDateTime nextPollAt;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    /** 연속 실패 횟수. 백오프의 근거이자 "이 배송만 계속 실패한다"의 신호다. */
    @Column(name = "poll_failures", nullable = false)
    private int pollFailures;

    @OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("occurredAt ASC")
    private List<DeliveryEvent> events = new ArrayList<>();

    @OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ShipmentItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    public Shipment(Delivery delivery, int sequenceNo, boolean international) {
        this.delivery = delivery;
        this.sequenceNo = sequenceNo;
        this.international = international;
        this.status = DeliveryStatus.PENDING;
        this.pollFailures = 0;
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
        this.updatedAt = this.createdAt;
    }

    /**
     * 송장번호를 등록한다. 이 시점부터 폴링이 시작된다.
     *
     * <p>추적을 지원하지 않는 택배사면 폴링하지 않는다 — 호출해 봐야 응답이 없다.
     */
    public void assignTracking(String carrierCode, String trackingNumber, boolean supportsTracking) {
        this.carrierCode = carrierCode;
        this.trackingNumber = trackingNumber;
        this.status = DeliveryStatus.INFO_RECEIVED;
        this.dispatchedAt = LocalDateTime.now(ZoneOffset.UTC);
        this.pollFailures = 0;
        // 즉시 한 번 조회한다. 등록 직후 사용자가 화면을 보는 경우가 많다.
        this.nextPollAt = supportsTracking ? LocalDateTime.now(ZoneOffset.UTC) : null;
        touch();
    }

    /**
     * 상태를 진행시킨다.
     *
     * @return 실제로 바뀌었으면 true. 폴링마다 이벤트를 발행하면 3시간마다 알림이 가므로,
     *         <b>바뀐 경우에만</b> 발행하기 위해 필요하다.
     */
    public boolean advanceTo(DeliveryStatus next, LocalDateTime occurredAt) {
        if (next == null || !next.isProgressFrom(this.status)) {
            return false;
        }
        this.status = next;
        if (next == DeliveryStatus.DELIVERED) {
            this.deliveredAt = occurredAt != null ? occurredAt : LocalDateTime.now(ZoneOffset.UTC);
        }
        reschedulePoll();
        touch();
        return true;
    }

    /** 발송 전 취소. 이미 움직이는 물건은 취소가 아니라 반품이다. */
    public boolean cancel() {
        if (!status.isBeforeDispatch()) {
            return false;
        }
        this.status = DeliveryStatus.CANCELLED;
        this.nextPollAt = null;
        touch();
        return true;
    }

    public void setEstimatedAt(LocalDateTime estimatedAt) {
        this.estimatedAt = estimatedAt;
        touch();
    }

    public void setCustomsInfo(String clearanceNo, String declarationNo) {
        if (clearanceNo != null) {
            this.customsClearanceNo = clearanceNo;
        }
        if (declarationNo != null) {
            this.customsDeclarationNo = declarationNo;
        }
        touch();
    }

    /** 조회에 성공했다. 실패 카운터를 지우고 다음 주기를 잡는다. */
    public void recordSyncSuccess() {
        recordSyncSuccess(null);
    }

    /**
     * 조회에 성공했다 — 택배사가 알려 준 갱신 주기({@code cap})가 있으면 그것과 상태별 기본
     * 주기 중 <b>짧은 쪽</b>으로 다음 조회를 잡는다.
     *
     * <p>🐛 상한이 없을 때는 접수(INFO_RECEIVED) 뒤 6시간을 기다렸다. 시뮬레이터는 1분마다
     * 단계를 밟는데 6시간 뒤에 한 번 물으면 이력 전체가 한꺼번에 와서 곧장 배달 완료로
     * 뛰었다 — 집화·이동·배달 출발과 그 알림이 전부 사라졌다.
     *
     * @param cap 택배사의 갱신 주기. null 이면 기본 주기만 쓴다
     */
    public void recordSyncSuccess(Duration cap) {
        this.lastSyncedAt = LocalDateTime.now(ZoneOffset.UTC);
        this.pollFailures = 0;
        reschedulePoll(cap);
        touch();
    }

    /**
     * 조회에 실패했다. 지수 백오프로 물러선다.
     *
     * <p>택배사 API 가 죽었을 때 같은 주기로 계속 때리면 복구를 방해하고 우리 쿼터만 태운다.
     * 상한(12시간)을 두는 이유는, 무한히 물러서면 복구된 뒤에도 한참 뒤에야 다시 조회하기
     * 때문이다.
     */
    public void recordSyncFailure() {
        this.pollFailures += 1;
        long backoffMinutes = Math.min(15L * (1L << Math.min(pollFailures, 5)), 12 * 60L);
        this.nextPollAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(backoffMinutes);
        touch();
    }

    private void reschedulePoll() {
        reschedulePoll(null);
    }

    private void reschedulePoll(Duration cap) {
        Duration interval = status.pollInterval();
        // 종료된 배송은 더 조회하지 않는다. 이게 쿼터 낭비의 가장 큰 원인이다. 상한이 있어도
        // 끝난 배송을 되살리지는 않는다 — 상한은 줄이는 데만 쓴다.
        if (interval == null || trackingNumber == null) {
            this.nextPollAt = null;
            return;
        }
        if (cap != null && !cap.isNegative() && !cap.isZero() && cap.compareTo(interval) < 0) {
            interval = cap;
        }
        this.nextPollAt = LocalDateTime.now(ZoneOffset.UTC).plus(interval);
    }

    public void addEvent(DeliveryEvent event) {
        this.events.add(event);
    }

    public void addItem(ShipmentItem item) {
        this.items.add(item);
    }

    private void touch() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
