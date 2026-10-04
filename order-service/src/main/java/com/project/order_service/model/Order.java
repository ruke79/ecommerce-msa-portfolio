package com.project.order_service.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.project.common.constants.OrderStatus;

import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import com.project.common.constants.PaymentProvider;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@Entity
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name="orders", schema = "orders", 
        indexes = {
                @Index(columnList = "trackingId, customerId, paymentMethod", name = "idx_order") }
                , uniqueConstraints = {
                        @UniqueConstraint(name = "uk_order_trackingId", columnNames = {"trackingId"}
                )                
        })        
public class Order extends BaseEntity {
   
   @Id @Tsid    
    @Column(name = "order_id")
    private Long orderId;

    
    private String trackingId;

    
    private Long customerId;
            
    @Builder.Default
    @JsonManagedReference // OrderProduct로의 순환 참조를 관리합니다.
    @OneToMany(mappedBy="order", fetch = FetchType.LAZY,
    cascade = CascadeType.PERSIST,targetEntity = OrderProduct.class)
    private List<OrderProduct> orderedProducts = new ArrayList();

    
    // 주문 → 주소 한 방향(V25). 주소는 주문마다 따로 만드는 사본이라 일대일이다(uq_orders_order_address).
    @jakarta.persistence.OneToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="order_address_id", referencedColumnName = "order_address_id")
    private OrderAddress shippingAddress;
    

    private String paymentMethod;

    /**
     * 상점 통화의 주문 금액(V7 부터 {@code numeric(19,2)}).
     *
     * <p>🐛 예전에는 {@code int} 였다. $59.98 이 59 로 저장되고 <b>실제로 98센트를 덜
     * 받았다</b> — 프론트가 제공자에 보내는 금액도 같은 59 라 검증은 통과했다. 청구액
     * ({@link #chargedAmount})도 그 잘린 값에서 환산됐으므로 같은 오차를 물려받았다.
     *
     * <p>네 금액은 한 식으로 묶여 있다: {@code total = totalBeforeDiscount − 할인},
     * {@code shippingPrice ⊂ total}. 그래서 넷을 함께 옮겼다.
     *
     * <p>기본값 0 은 {@code int} 시절과 같다 — 빌더가 채우지 않은 금액이 {@code null} 로
     * NOT NULL 컬럼에 가지 않게 한다.
     */
    @Builder.Default
    @Column(precision = 19, scale = 2, nullable = false)
    private java.math.BigDecimal total = java.math.BigDecimal.ZERO;

    @Builder.Default
    @Column(precision = 19, scale = 2, nullable = false)
    private java.math.BigDecimal shippingPrice = java.math.BigDecimal.ZERO;

    @Builder.Default
    @Column(precision = 19, scale = 2, nullable = false)
    private java.math.BigDecimal totalBeforeDiscount = java.math.BigDecimal.ZERO;

    private String couponApplied;

    // 세금 금액(taxPrice)은 없앴다 — 아무도 채우지 않아 늘 0 이었고, 화면은 그것을 "세금 무료" 로 보였다. 가격은 세금 포함이다(V25).


    @Enumerated(EnumType.STRING)
    private OrderStatus orderStatus;    
    
    /**
     * 결제가 확인된 시각 — {@link #markPaid(LocalDateTime)} 가 처음 한 번만 적는다.
     *
     * <p>🐛 예전: 무통장입금 수동 확인에서만 채워졌다. 카드 · PayPal · Stripe · 포트원은 사가가 상태만 PAID 로 바꿔
     * 비어 있었고, 관리자 환불 화면은 주문 생성 시각으로 대신 보여 줬다.
     */
    // [JA] 決済が確認された時刻 — markPaid() が最初の一回だけ書く。以前は銀行振込の手動確認でしか埋まらなかった。
    private LocalDateTime paidCreatedAt;

    /** 결제 확인 — 상태를 PAID 로, 결제 시각은 처음 한 번만(재전달로 다시 와도 처음 시각을 지킨다). */
    public void markPaid(LocalDateTime at) {
        this.orderStatus = OrderStatus.PAID;
        if (this.paidCreatedAt == null) {
            this.paidCreatedAt = at;
        }
    }

    private LocalDateTime deliveredCreatedAt;

    /**
     * 구매 확정(V13, docs/25 §12). {@code DELIVERED} 뒤에 손님이 누르거나, 기한이 지나
     * 자동으로 {@code COMPLETED} 가 될 때 찍힌다. {@code autoCompleted} 가 참이면 후자다 —
     * 화면은 "자동으로 확정되었습니다" 라고 따로 말한다.
     */
    private LocalDateTime completedAt;

    @Builder.Default
    @Column(nullable = false)
    private boolean autoCompleted = false;

    /**
     * 무통장입금(V10, docs/49 §11). {@code UNPAID} 인 동안만 뜻이 있다 — 입금 기한이 지나면
     * 만료 잡이 주문을 접고, 입금자명은 관리자가 은행 내역과 맞출 실마리다.
     */
    private LocalDateTime depositDeadline;

    /**
     * 결제 전 재고 예약이 언제까지인가(V22, docs/59). 재고는 catalog 가 잡고 여기는 만료 시각만 — 결제 화면이 안내한다.
     * null 이면 예약 없음(이 기능 전의 주문, 또는 catalog 에 닿지 못해 예약 없이 만든 주문).
     */
    @Column(name = "stock_hold_until")
    private LocalDateTime stockHoldUntil;

    @Column(length = 50)
    private String depositorName;

    /**
     * 포트원 가상계좌(V11). 발급되면 여기 남고 손님에게 이 계좌를 보여 준다 — 설정의 상점
     * 계좌 대신. {@code pendingPaymentRef} 는 발급된 결제의 참조로, 입금 전 취소 때 PG 의
     * 계좌를 닫는 데 쓴다. 입금 확인은 관리자가 아니라 포트원 웹훅이 한다.
     */
    @Column(length = 50)
    private String vbankBank;

    @Column(length = 64)
    private String vbankAccount;

    @Column(length = 50)
    private String vbankHolder;

    private String pendingPaymentRef;

    /** 발급된 가상계좌로 입금을 기다리는 주문인가. */
    public boolean hasVirtualAccount() {
        return vbankAccount != null && !vbankAccount.isBlank();
    }

    /**
     * 이 결제를 확인해 준 곳 — PAYPAL · STRIPE.
     *
     * <p>{@code paymentMethod}(사용자가 고른 수단)와 다르다. 이쪽은 <b>서버가 결제를
     * 확인하러 물어봐야 할 상대</b>다({@link com.project.common.constants.PaymentProvider}).
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PaymentProvider paymentProvider;

    /**
     * 제공자가 확인해 준 결제 참조 — PayPal 이면 PayPal 주문 ID. 결제가 검증된 뒤에만 채워진다.
     *
     * <p>{@code (payment_provider, provider_payment_id)} 에 부분 유니크 인덱스가 걸려 있어
     * (V4) 같은 결제를 두 번째 주문에 붙이려 하면 DB 가 거부한다. 애플리케이션에서
     * 확인하면 동시 요청 둘이 함께 통과할 수 있다.
     *
     * <p>예전의 PayPal 전용 컬럼 {@code paypal_order_id}(V3)는 더 매핑하지 않는다 — V17 이 남은 값을 이 컬럼으로
     * 옮겼고, 컬럼은 이 릴리스가 안정된 뒤 지운다(docs/49 §3). 롤링 중 옛 파드가 그 컬럼을 읽기 때문에 같은
     * 릴리스에서 지우지 않는다.
     */
    private String providerPaymentId;

    /**
     * 제공자의 <b>마지막</b> 환불 참조(V5). 부분 환불(V9)부터는 한 주문에 환불이 여럿일 수
     * 있어 건별 기록은 {@link #refunds} 에 있다. "이미 다 환불됐는가" 는 이 값이 아니라
     * {@link #refundedAmount} 가 {@link #total} 에 닿았는지로 본다.
     */
    private String refundId;

    private LocalDateTime refundedAt;

    /**
     * 누적 환불액(V9) — 상점 통화와 청구 통화. 부분 환불이 쌓인다. 마지막 환불은 언제나
     * "남은 전부"({@code total − refundedAmount})라 배송비·반올림 찌꺼기가 거기서 정리된다.
     */
    @Builder.Default
    @Column(precision = 19, scale = 2, nullable = false)
    private java.math.BigDecimal refundedAmount = java.math.BigDecimal.ZERO;

    @Builder.Default
    @Column(precision = 19, scale = 2, nullable = false)
    private java.math.BigDecimal refundedChargedAmount = java.math.BigDecimal.ZERO;

    /** 환불 한 건 한 건(V9). 주문을 저장하면 함께 저장된다(cascade) — 별도 저장소가 없다. */
    @Builder.Default
    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<OrderRefund> refunds = new ArrayList<>();

    /** 아직 돌려주지 않은 돈(상점 통화). 0 이면 다 돌려줬다. */
    public java.math.BigDecimal remainingRefundable() {
        java.math.BigDecimal remaining = total.subtract(refundedAmount);
        return remaining.signum() < 0 ? java.math.BigDecimal.ZERO : remaining;
    }

    /**
     * 반품·재고 복원(V8).
     *
     * <p>{@code awaitingReturn} — 발송된 뒤 환불돼 <b>물건이 돌아와야 하는</b> 주문. 발송 전
     * 환불은 물건이 안 나갔으므로 거짓이고 재고가 바로 돌아온다. 참이면 관리자가 입고를
     * 기록할 때({@code returnReceivedAt}) 거짓이 되고 그때 재고가 돌아온다.
     *
     * <p>{@code stockRestoredAt} — catalog 에 복원 이벤트를 보낸 시각. <b>한 번만</b> 찍힌다.
     * null 이면 아직이거나, 파손 등으로 복원하지 않기로 한 것이다.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean awaitingReturn = false;

    private LocalDateTime returnReceivedAt;

    /**
     * 재고 부족(V21, docs/53) — catalog 가 "결제됐지만 n개 모자라다" 고 알린 이벤트의 멱등 키와, 자동 환불이 끝났거나
     * 운영자에게 넘긴 시각. 키가 있는데 시각이 비어 있으면 아직 처리 중이다(재시도 대상).
     */
    @Column(length = 80)
    private String shortageEventId;

    private LocalDateTime shortageResolvedAt;

    private LocalDateTime stockRestoredAt;

    /**
     * 청구 통화·금액·환율(V6) — 제공자에게 <b>실제로 요청한</b> 결제.
     *
     * <p>{@code total} 은 상점 통화(매출·집계의 기준)이고 바뀌지 않는다. 결제수단의 통화가
     * 상점 통화와 다르면(포트원 KRW · 일본 테스트 JPY) 주문 생성 시점의 환율로 청구액을
     * 정해 여기 저장한다. 결제 확인·환불은 <b>이 값</b>과 대조한다 — 그 사이 환율이
     * 바뀌어도 "그때 청구한 금액" 이 기준이다.
     *
     * <p>null 이면 "상점 통화로 total 만큼" — 이 컬럼이 생기기 전 주문의 의미 그대로다.
     */
    @Column(length = 3)
    private String currency;

    private java.math.BigDecimal chargedAmount;

    private java.math.BigDecimal fxRate;

}
