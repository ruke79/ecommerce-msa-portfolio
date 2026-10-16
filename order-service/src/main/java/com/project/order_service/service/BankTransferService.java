package com.project.order_service.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.project.common.constants.OrderStatus;
import com.project.order_service.model.Order;
import com.project.order_service.payment.PaymentCurrencyPolicy;
import com.project.order_service.payment.PaymentVerificationException;
import com.project.order_service.saga.OrderSaga;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import com.project.order_service.stock.OrderStockHold.Outcome;

/**
 * 무통장입금 — 주문 상태 기계 (docs/49 §11).
 *
 * <pre>
 *   NOT_PROCESSED ──(입금 안내 받기: 고객)──▶ UNPAID ──(입금 확인: 관리자)──▶ PAID
 *                                              │
 *                                              └──(기한 경과 · 손님이 접음)──▶ CANCELED
 * </pre>
 *
 * <p>결제 API 가 없다. "확인해 줄 상대" 는 은행 내역을 본 관리자이고, 확인 뒤는 카드와
 * 같은 길이다 — 아웃박스 → pay-service → 응답 → 사가가 {@code PAID} 로 바꾸고 재고를
 * 차감한다. 그래서 여기서는 <b>상태와 기한</b>만 다룬다. 규칙은 {@code OrderDataService}
 * (자기 트랜잭션)가 갖고, 보상 발행은 그 밖에서 한다(환불과 같은 모양).
 *
 * <p>계좌 정보는 설정이다({@code bank-transfer.*}). 주문마다 다르지 않고 비밀도 아니다 —
 * 손님에게 보여 주는 값이다. 다만 비어 있으면 안내를 시작할 수 없다(계좌 없는 안내는
 * 입금할 곳이 없다).
 *
 * <p>🔒 입금 확인은 <b>관리자만</b>. 이 한 번의 호출이 카드 결제 확인과 같은 무게다 —
 * 재고가 나가고 매출이 잡힌다.
 */
@Slf4j
@Service
@ConfigurationProperties(prefix = "bank-transfer")
@Getter
@Setter
public class BankTransferService {

    private final OrderCacheService cahcedOrderService;
    private final OrderDataService orderDataService;
    private final OrderSaga orderSaga;
    private final PaymentCurrencyPolicy currencyPolicy;
    private final com.project.order_service.payment.PaymentGateways paymentGateways;

    /** 기본 켜짐 — 자격증명이 필요 없다. 끄면 안내 시작이 거부된다. */
    private boolean enabled = true;
    private String bank = "";
    private String account = "";
    private String holder = "";
    /** 입금 기한(시간). 지나면 만료 잡이 접는다. */
    private int deadlineHours = 48;

    /**
     * 한 고객이 동시에 둘 수 있는 입금 대기(UNPAID) 주문 수. 입금 안내마다 재고를 입금 기한(+1시간)까지 잡으므로 — 🐛 예전에는 상한이
     * 없어 돈을 내지 않고 안내만 반복해 인기 상품을 49시간씩 묶어 둘 수 있었다.
     * [JA] 一人の顧客が同時に持てる入金待ち注文の数。以前は上限がなく、払わずに案内だけを繰り返して在庫を 49 時間ずつ押さえられた。
     */
    private int maxPendingPerCustomer = 3;

    public static final String TOO_MANY_PENDING_MESSAGE = "입금을 기다리는 주문이 너무 많습니다. 기존 주문을 입금하거나 취소한 뒤 다시 시도해 주세요.";

    /** 결제 전 재고 예약(docs/59). 세터로 받는다 — 생성자를 쓰는 곳을 늘리지 않으려고. 없으면(테스트) 예약을 건드리지 않는다. */
    private com.project.order_service.stock.OrderStockHold stockHold;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setStockHold(com.project.order_service.stock.OrderStockHold stockHold) {
        this.stockHold = stockHold;
    }

    /** 입금 안내 때 쿠폰 잡기(coupon V7). 세터로 받는다. 없으면(테스트) 건드리지 않는다. */
    private OrderCouponClaim couponClaim;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCouponClaim(OrderCouponClaim couponClaim) {
        this.couponClaim = couponClaim;
    }

    public BankTransferService(OrderCacheService cahcedOrderService, OrderDataService orderDataService,
            OrderSaga orderSaga, PaymentCurrencyPolicy currencyPolicy,
            com.project.order_service.payment.PaymentGateways paymentGateways) {
        this.cahcedOrderService = cahcedOrderService;
        this.orderDataService = orderDataService;
        this.orderSaga = orderSaga;
        this.currencyPolicy = currencyPolicy;
        this.paymentGateways = paymentGateways;
    }

    /**
     * 포트원이 발급한 가상계좌를 주문에 붙인다 — "입금 안내" 를 PG 가 만든 경우. 발급 웹훅과
     * 브라우저 복귀가 둘 다 부른다(멱등). 입금 확인은 그 뒤 포트원의 결제 완료 웹훅이 한다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Order registerVirtualAccount(String trackingId, String paymentRef,
            com.project.order_service.payment.portone.VirtualAccount va, Long requesterId) {
        Order order = cahcedOrderService.registerVirtualAccount(trackingId, paymentRef, va, requesterId, deadlineHours);
        // 가상계좌는 PG 가 이미 발급했다 — 여기서 거절하기엔 늦다. 입금 기한까지 늘리되, 모자라면 알리기만 한다(그 주문이
        // 결제되면 부족분은 무통장이라 수동 환불 경보로 간다 — docs/53 §7).
        Outcome hold = extendHold(order);
        if (hold != null && hold.isShort()) {
            log.warn("가상계좌 주문의 재고를 입금 기한까지 잡지 못했다 — 입금되면 부족분 수동 환불이 필요할 수 있다. orderId={}",
                    order.getOrderId());
        }
        return order;
    }

    /** 고객이 입금 안내를 받는다. 안내 내용(계좌·금액·기한)을 돌려준다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Object> start(String trackingId, Long requesterId, String depositorName) {
        requireConfigured();
        // 입금 안내를 내기 전에 재고를 입금 기한까지 잡는다(docs/59). 결제 화면에 머무는 사이 예약이 만료돼 남에게 팔렸다면
        // 여기서 멈춘다 — 없는 물건에 손님이 돈을 보내게 하지 않는다(무통장은 자동 환불도 안 된다).
        Order current = orderDataService.getOrderByTrackingId(trackingId);
        if (current != null && requesterId != null && requesterId.equals(current.getCustomerId())
                && current.getOrderStatus() == OrderStatus.NOT_PROCESSED) {
            if (orderDataService.countAwaitingDeposit(requesterId) >= maxPendingPerCustomer) {
                log.warn("입금 대기 주문 상한 — 새 입금 안내를 거절한다. customerId={} max={}", requesterId, maxPendingPerCustomer);
                throw new PaymentVerificationException(TOO_MANY_PENDING_MESSAGE);
            }
            Outcome hold = extendHold(current);
            if (hold != null && hold.isShort()) {
                throw new PaymentVerificationException(
                        OrderService.shortageMessage(current, hold.shortages()).replace("주문하지 못했습니다", "입금을 시작하지 못했습니다"));
            }
            if (hold != null && hold.isRejected()) {
                throw new PaymentVerificationException(OrderService.UNORDERABLE_MESSAGE);
            }
            // 쿠폰을 입금 기한(+1시간)까지 이 주문에 잡는다(coupon V7) — 입금을 기다리는 동안 같은 쿠폰으로 다른 주문을 결제하지 못하게.
            if (couponClaim != null) {
                couponClaim.claim(current, java.time.Duration.ofHours(deadlineHours + 1L));
            }
        }
        Order order = cahcedOrderService.startBankTransfer(trackingId, requesterId, depositorName, deadlineHours);
        return notice(order);
    }

    /** 입금 기한(+1시간 여유)까지 재고를 잡는다. 예약 기능이 없으면 null. 잡았으면 만료 시각을 주문에 적는다. */
    private Outcome extendHold(Order order) {
        if (stockHold == null || order == null) {
            return null;
        }
        Outcome hold = stockHold.hold(order, java.time.Duration.ofHours(deadlineHours + 1L));
        if (hold.isHeld() && hold.holdUntil() != null) {
            cahcedOrderService.recordStockHold(order.getOrderId(), hold.holdUntil());
        }
        return hold;
    }

    /**
     * 입금 안내 다시 보기 — 주문 상세를 새로 열었을 때. 🔒 주문자만. 없거나 남의 주문이면
     * {@code null} — 둘을 구분하지 않는다(404 와 같은 이유).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> notice(String trackingId, Long requesterId) {
        Order order = orderDataService.getOrderByTrackingId(trackingId);
        if (order == null) {
            return null;
        }
        if (requesterId == null || !requesterId.equals(order.getCustomerId())) {
            return null;
        }
        return notice(order);
    }

    private Map<String, Object> notice(Order order) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trackingId", order.getTrackingId());
        m.put("orderStatus", order.getOrderStatus());
        // 가상계좌가 발급된 주문은 그 계좌다 — 상점 계좌로 보내면 PG 가 입금을 못 본다.
        boolean vbank = order.hasVirtualAccount();
        m.put("provider", vbank ? "PORTONE" : "BANK_TRANSFER");
        m.put("bank", vbank ? order.getVbankBank() : bank);
        m.put("account", vbank ? order.getVbankAccount() : account);
        m.put("holder", vbank ? order.getVbankHolder() : holder);
        BigDecimal amount = order.getChargedAmount() != null ? order.getChargedAmount() : order.getTotal();
        String currency = order.getCurrency() != null ? order.getCurrency() : currencyPolicy.baseCurrency();
        m.put("amount", PaymentCurrencyPolicy.plain(amount, currency));
        m.put("currency", currency);
        m.put("depositorName", order.getDepositorName());
        m.put("depositDeadline", order.getDepositDeadline());
        return m;
    }

    /** 🔒 관리자가 입금을 확인했다. 그 뒤는 카드와 같은 길이다(아웃박스 → 사가). */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Order confirmDeposit(String trackingId, String depositReference) {
        return cahcedOrderService.confirmDeposit(trackingId, depositReference);
    }

    /**
     * 입금 전에 접는다 — 고객 스스로, 또는 관리자. 쿠폰을 돌려준다.
     *
     * <p>가상계좌 주문이면 <b>PG 의 계좌를 먼저 닫는다.</b> 우리만 접고 계좌를 열어 두면
     * 손님이 기한 안에 입금할 수 있고, 그 돈은 "취소된 주문에 도착한 결제" 가 된다
     * ({@code recordPayment} 가 거절하고 수동 환불이 필요하다). 닫지 못하면 접지도 않는다 —
     * 기한까지 기다리는 편이 그 상황보다 낫다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Order cancel(String trackingId, Long requesterId, boolean admin) {
        Order current = orderDataService.getOrderByTrackingId(trackingId);
        if (current != null && current.hasVirtualAccount() && current.getPendingPaymentRef() != null
                && current.getOrderStatus() == OrderStatus.UNPAID) {
            BigDecimal amount = current.getChargedAmount() != null ? current.getChargedAmount() : current.getTotal();
            String currency = current.getCurrency() != null ? current.getCurrency() : currencyPolicy.baseCurrency();
            try {
                paymentGateways.requireRefunding(com.project.common.constants.PaymentProvider.PORTONE)
                        .refund(current.getPendingPaymentRef(), amount, currency, "입금 전 취소",
                                "va-cancel-" + current.getOrderId());
            } catch (RuntimeException e) {
                throw new PaymentVerificationException(
                        "가상계좌를 닫지 못해 취소하지 않았습니다 — 기한(" + current.getDepositDeadline()
                                + ")이 지나면 자동으로 취소됩니다. " + e.getMessage(), e);
            }
        }
        Order canceled = cahcedOrderService.cancelUnpaid(trackingId, requesterId, admin);
        if (canceled.getOrderStatus() == OrderStatus.CANCELED) {
            orderSaga.compensateCancel(canceled);
            if (stockHold != null) {
                stockHold.release(canceled.getOrderId());   // 잡아 둔 재고를 푼다(docs/59)
            }
        }
        return canceled;
    }

    /**
     * 기한이 지난 입금 대기 주문을 접는다. 한 시간마다. 기동 직후 1분은 기다린다 —
     * 다른 빈이 준비되기 전에 돌지 않게.
     */
    @Scheduled(fixedDelayString = "${bank-transfer.expiry-check-ms:3600000}", initialDelay = 60000)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void expire() {
        if (!enabled) {
            return;
        }
        List<Order> canceled = cahcedOrderService.expireUnpaid(LocalDateTime.now());
        for (Order order : canceled) {
            orderSaga.compensateCancel(order);
            if (stockHold != null) {
                stockHold.release(order.getOrderId());   // 입금 기한이 지났다 — 재고를 푼다(docs/59)
            }
        }
        if (!canceled.isEmpty()) {
            log.info("입금 기한 경과로 {}건을 접었다", canceled.size());
        }
    }

    private void requireConfigured() {
        if (!enabled) {
            throw new PaymentVerificationException("무통장입금이 꺼져 있습니다(BANK_TRANSFER_ENABLED).");
        }
        if (bank.isBlank() || account.isBlank() || holder.isBlank()) {
            // 계좌 없는 안내는 입금할 곳이 없다. 500 이 아니라 설정 문제라고 말한다.
            throw new PaymentVerificationException(
                    "입금 계좌가 설정되지 않았습니다(BANK_TRANSFER_BANK · ACCOUNT · HOLDER). 관리자에게 문의해 주세요.");
        }
    }
}
