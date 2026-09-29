package com.project.catalog_service.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 결제 전 재고 예약 한 줄 — 주문 하나의 한 옵션(상품·색상·사이즈) (docs/59, V14).
 *
 * <p>수량을 옮기지 않는다. {@code product_size.quantity} 는 창고의 실물 그대로고, 예약은 "이만큼은 누가 사는 중" 이라는
 * 표시다. 남은 수량 = quantity − 살아 있는 HELD 의 합.
 */
@Entity
@Table(name = "stock_reservation", schema = "product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockReservation {

    public enum Status {
        /** 잡아 두었다 — 결제를 기다린다. */
        HELD,
        /** 결제가 확정돼 실제 차감으로 넘어갔다. */
        COMMITTED,
        /** 취소·만료로 풀렸다. */
        RELEASED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_id")
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "color_id", nullable = false, updatable = false)
    private Long colorId;

    @Column(name = "size", nullable = false, updatable = false)
    private String size;

    @Column(name = "qty", nullable = false)
    private int qty;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static StockReservation hold(Long orderId, Long productId, Long colorId, String size, int qty,
            Instant expiresAt, Instant now) {
        StockReservation r = new StockReservation();
        r.orderId = orderId;
        r.productId = productId;
        r.colorId = colorId;
        r.size = size;
        r.qty = qty;
        r.status = Status.HELD;
        r.expiresAt = expiresAt;
        r.createdAt = now;
        r.updatedAt = now;
        return r;
    }

    /** 다시 잡는다 — 수량·만료를 새로 쓴다(재시도·무통장 전환). 풀렸던 행도 다시 HELD 가 된다. */
    public void rehold(int qty, Instant expiresAt, Instant now) {
        this.qty = qty;
        this.status = Status.HELD;
        this.expiresAt = expiresAt;
        this.updatedAt = now;
    }

    public void commit(Instant now) {
        this.status = Status.COMMITTED;
        this.updatedAt = now;
    }

    public void release(Instant now) {
        this.status = Status.RELEASED;
        this.updatedAt = now;
    }

    /** 살아 있는 예약인가 — HELD 이고 아직 만료 전. */
    public boolean isActive(Instant now) {
        return status == Status.HELD && expiresAt.isAfter(now);
    }
}
