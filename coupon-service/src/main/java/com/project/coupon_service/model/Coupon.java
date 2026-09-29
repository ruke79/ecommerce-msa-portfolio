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
indexes = {
        @Index(columnList = "name, user_id", name = "idx_coupon") })
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
