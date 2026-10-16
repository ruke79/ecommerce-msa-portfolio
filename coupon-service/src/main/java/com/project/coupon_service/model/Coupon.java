package com.project.coupon_service.model;

import java.time.LocalDateTime;

import com.project.common.constants.coupon.CouponScope;
import com.project.common.constants.coupon.CouponStatus;

import io.hypersistence.utils.hibernate.id.Tsid;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import lombok.Setter;
import lombok.experimental.SuperBuilder;


@Getter
@Setter
@Entity
@SuperBuilder // @Builder 대신 @SuperBuilder 사용
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name="coupon", schema = "coupon",
       uniqueConstraints = {
        @jakarta.persistence.UniqueConstraint(columnNames = {"name", "user_id"}, name = "uk_coupon_name_user_id")
       },
// (name, user_id) 조회는 유니크 제약의 인덱스가 받는다. 사용자의 쓰지 않은 쿠폰 목록은 (user_id, status) — V8.
indexes = {
        @Index(columnList = "user_id, status", name = "idx_coupon_user_status") })
public class Coupon extends BaseEntity{

    @Id @Tsid    
    @Column(name="coupon_id")    
    private Long couponId;

    private Long userId;

    private String name;

    @Column(name="start_date")
    private LocalDateTime startDate;

    @Column(name="end_date")
    private LocalDateTime endDate;

    private int discount;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "status")
    private CouponStatus couponStatus;

    /**
     * 적용 범위. null 은 {@link CouponScope#ALL} 로 읽는다 — V5 이전에 만들어진 쿠폰이
     * 그대로 동작해야 하기 때문이다. 읽을 때는 {@link #scope()} 를 쓴다.
     */
    /**
     * 이 쿠폰을 쓴 주문(결제 확정 때 적는다, V6). 보상(coupon-rollback)은 이 주문의 것일 때만 되돌린다. 쓰기 전 · 옛 USED 행은 null.
     * [JA] このクーポンを使った注文。補償はこの注文のときだけ戻す。
     */
    @Column(name = "used_order_id")
    private Long usedOrderId;

    /**
     * 이 쿠폰으로 결제 중인 주문과 그 끝(V7). 결제를 시작할 때 잡고(order 가 부른다) 결제 확정 · 보상이 푼다. 끝이 지나면 풀린 것으로
     * 본다 — 결제창을 닫고 떠난 주문이 쿠폰을 영영 붙들지 않게.
     * [JA] このクーポンで決済中の注文とその期限。決済開始で押さえ、確定 · 補償で外す。期限を過ぎれば外れたとみなす。
     */
    @Column(name = "payment_order_id")
    private Long paymentOrderId;

    @Column(name = "payment_until")
    private LocalDateTime paymentUntil;

    /** 다른 주문이 지금 이 쿠폰으로 결제 중인가. {@code orderId} 가 null 이면 어느 주문이든. */
    public boolean inPaymentByAnotherOrder(Long orderId, LocalDateTime now) {
        return paymentOrderId != null && !paymentOrderId.equals(orderId)
                && paymentUntil != null && paymentUntil.isAfter(now);
    }

    /** 결제 중 표시를 지운다(결제 확정 · 보상). */
    public void clearPayment() {
        paymentOrderId = null;
        paymentUntil = null;
    }

    @Enumerated(value = EnumType.STRING)
    @Column(name = "scope", length = 16)
    private CouponScope scope;

    /** null 을 ALL 로 해석한다. 호출부가 매번 null 검사를 반복하지 않도록. */
    public CouponScope scope() {
        return scope == null ? CouponScope.ALL : scope;
    }

    /**
     * 지금 쓸 수 있는 기간인가.
     *
     * <p><b>예전에는 이 검사가 어디에도 없었다.</b> {@code startDate}/{@code endDate} 를
     * 저장만 하고 적용 시점에 보지 않았다 — 가입 쿠폰의 31일 유효기간은 장식이었고,
     * 1년 전에 만료된 쿠폰도 그대로 할인됐다. 쿠폰을 찾는 쿼리는 이름·사용자·상태만 봤다.
     *
     * <p>경계는 양쪽 다 포함이다. 날짜가 비어 있으면 그 방향으로는 제한이 없다고 본다
     * (관리자가 시작일만 정하고 종료일을 비워 두는 경우).
     */
    public boolean isUsableAt(LocalDateTime when) {
        if (startDate != null && when.isBefore(startDate)) {
            return false;
        }
        if (endDate != null && when.isAfter(endDate)) {
            return false;
        }
        return true;
    }
}
