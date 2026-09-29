package com.project.coupon_service.model;

import com.project.common.constants.coupon.CouponScope;

import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * 쿠폰이 적용되는 대상 하나 — 카테고리 id 하나 또는 상품 id 하나.
 *
 * <p><b>왜 Coupon 안의 컬렉션이 아니라 별도 엔티티인가.</b> {@code @ElementCollection} 이
 * 더 짧지만 Hibernate 가 만드는 DDL 이 정확히 무엇인지에 스키마가 묶인다. 이 프로젝트는
 * {@code ddl-auto: validate} + Flyway 소유 구조라(docs/20), 마이그레이션이 Hibernate 의
 * 기대와 한 글자라도 어긋나면 <b>서비스가 기동하지 않는다.</b> 평범한 엔티티로 두면
 * 컬럼을 우리가 정하고 마이그레이션도 그대로 적으면 된다.
 *
 * <p>대상을 문자열로 두는 이유: 카테고리 id 와 상품 id 는 둘 다 TSID(long)이지만, 나중에
 * 브랜드·태그처럼 문자열 키를 쓰는 대상이 생겨도 스키마를 바꾸지 않아도 된다.
 */
@Getter
@Setter
@Entity
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "coupon_target", schema = "coupon",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = { "coupon_id", "target_type", "target_value" },
                             name = "uk_coupon_target")
       },
       indexes = { @Index(columnList = "coupon_id", name = "idx_coupon_target_coupon") })
public class CouponTarget {

    @Id
    @Tsid
    @Column(name = "coupon_target_id")
    private Long couponTargetId;

    @Column(name = "coupon_id", nullable = false)
    private Long couponId;

    /** {@link CouponScope#CATEGORY} 또는 {@link CouponScope#PRODUCT}. ALL 은 대상 행이 없다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private CouponScope targetType;

    /** 카테고리 id 또는 상품 id. */
    @Column(name = "target_value", nullable = false)
    private String targetValue;
}
