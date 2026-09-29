package com.project.order_service.model;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonBackReference;

import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * 환불 한 건(V9) — 부분 환불이 생기면서 "주문에 환불 참조 하나" 로는 모자라졌다.
 *
 * <p>금액은 둘이다: {@code amount} 는 상점 통화(매출에서 빠지는 돈), {@code chargedAmount}
 * 는 제공자에게 실제로 돌려달라고 한 돈(청구 통화). {@code items} 는 어느 품목 몇 개인지의
 * JSON — 화면·분쟁 대응용이고 규칙은 여기서 읽지 않는다(규칙은 품목의 수량 카운터를 본다).
 */
@Getter
@Setter
@Entity
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "order_refund", schema = "orders")
public class OrderRefund extends BaseEntity {

    @Id
    @Tsid
    @Column(name = "order_refund_id")
    private Long orderRefundId;

    @JsonBackReference
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", referencedColumnName = "order_id", nullable = false)
    private Order order;

    /** 제공자의 환불 참조. */
    private String refundRef;

    @Column(precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(precision = 19, scale = 2, nullable = false)
    private BigDecimal chargedAmount;

    @Column(length = 3)
    private String currency;

    @Column(length = 200)
    private String reason;

    /** {@code [{"orderProductId":…,"qty":…}]} */
    @Column(columnDefinition = "text")
    private String items;
}
