package com.project.pay_service.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import com.project.common.constants.PaymentProvider;
import com.project.common.constants.PaymentStatus;
import com.project.common.constants.PaymentType;

import io.hypersistence.utils.hibernate.id.Tsid;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@Entity
@Table(name="payment", schema = "pay", uniqueConstraints = {
                @UniqueConstraint(name="UniqueOrderIdAndTrackingId", columnNames = {"orderId", "trackingId"})                
     },indexes = {
        @Index(columnList = "orderId, trackingId, customerId", name = "idx_payment"),
        @Index(columnList = "paymentProvider, providerPaymentId", name = "idx_payment_provider_ref") })
public class Payment extends BaseEntity{

    @Id @Tsid    
    @Column(name = "payment_id")
    private Long paymentId;

    /**
     * 결제를 확인해 준 곳 — PAYPAL · STRIPE · PORTONE · BANK_TRANSFER (V5).
     *
     * <p>예전에는 PayPal 참조만 {@code paypal_order_id} 에 적었다 — Stripe·포트원·무통장 결제는 "어느 결제인가" 가 이
     * 서비스에 남지 않았다. 그 컬럼은 V5 가 옮기고 V6 가 한 번 더 옮긴 뒤 지웠다(docs/49 §3).
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PaymentProvider paymentProvider;

    /** 제공자가 확인해 준 결제 참조 — PayPal 이면 PayPal 주문 ID, Stripe 면 Checkout 세션 ID. */
    private String providerPaymentId;

    private Long orderId;    

    private Long customerId;

    private BigDecimal amounts;

    private String trackingId;
    
    @CreatedDate
    private LocalDateTime paymentCreatedAt;

    @LastModifiedDate   
    private LocalDateTime cancelCreatedAT;

    @Enumerated(EnumType.STRING)    
    private PaymentType paymentType;    

    @Enumerated(EnumType.STRING)
    private PaymentStatus paymentStatus;

    /**
     * 환불 반영 — {@code COMPLETED → CANCELED} 한 방향뿐이다.
     *
     * <p>setter 를 열지 않는 이유: 상태 전이가 아무 방향으로나 가능해지면 재전달 순서가
     * 역전됐을 때(CANCELED 뒤에 옛 COMPLETED 가 도착) 결제가 되살아난다. 이 메서드는
     * 이미 CANCELED 면 아무것도 하지 않는다.
     *
     * @return 상태가 바뀌었는가
     */
    public boolean markCanceled() {
        if (paymentStatus == PaymentStatus.CANCELED) {
            return false;
        }
        paymentStatus = PaymentStatus.CANCELED;
        return true;
    }
}
