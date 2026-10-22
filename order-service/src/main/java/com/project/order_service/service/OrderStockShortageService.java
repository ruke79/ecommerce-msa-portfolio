package com.project.order_service.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.project.common.constants.OrderStatus;
import com.project.common.constants.PaymentProvider;
import com.project.common.dto.request.OrderItemQtyRequest;
import com.project.order_service.model.Order;
import com.project.order_service.model.OrderProduct;
import com.project.order_service.repository.jpa.OrderRepository;

import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;

/**
 * 재고 부족 보상 — 결제됐지만 보낼 수 없는 수량을 적고 자동 환불한다 (docs/53 §초과 판매).
 *
 * <h3>🔴 왜 필요한가</h3>
 *
 * <p>재고는 <b>결제 뒤에</b> 차감한다. 마지막 1개를 두 사람이 동시에 결제하면 뒤의 차감이 모자란다. 예전에는 catalog 가
 * 로그 한 줄을 남기는 것이 전부였다 — 주문은 결제 완료로 진행되고, 환불도 경보도 없었다. 손님은 돈을 냈는데 물건은
 * 없고, 운영자는 배송 단계에서야(또는 손님의 문의로) 알았다.
 *
 * <p>이제 catalog 가 모자란 수를 {@code stock-shortage} 로 알리고(아웃박스), 여기서:
 * <ol>
 *   <li><b>적는다</b> — 줄마다 {@code shortQty}. 재고 복원·반품·배송 수량에서 빠진다(차감된 적이 없다). 멱등 키는
 *       주문의 {@code shortageEventId} — 재전달은 버린다.</li>
 *   <li><b>돌려준다</b> — 그 수만큼 부분 환불(관리자 권한의 기존 환불 경로 — 금액·통화·제공자 규칙이 그대로다). 손님은
 *       기존 환불 알림을 받는다. 전부 모자랐으면 주문 전체가 환불되고, 결제 취소가 배송도 취소한다.</li>
 *   <li><b>못 돌려주면 넘긴다</b> — 무통장입금은 계좌로 송금하는 사람이 필요하다. 로그 "수동 환불 필요" 가 Loki 경보
 *       {@code ManualActionRequired} 로 간다.</li>
 * </ol>
 *
 * <p>환불이 실패하면 예외를 던진다 — Kafka 가 다시 전달하고, 그때는 적는 단계를 건너뛰고(이미 적었다) 환불만 다시
 * 시도한다. 환불 수량은 "적힌 부족분 − 이미 환불된 수" 라 두 번 환불하지 않는다. 제공자 쪽은 환불 멱등 키가 같은 요청을
 * 한 번으로 접는다. 재시도가 다 실패하면 DLT 로 가고, 매번 남긴 "수동 환불 필요" 로 운영자가 안다.
 */
@Slf4j
@Service
public class OrderStockShortageService {

    static final String METRIC = "order.stock.shortage";
    static final String REFUND_REASON = "재고 부족 — 보낼 수 없는 수량을 자동 환불했습니다";

    public enum Outcome { DUPLICATE, UNKNOWN_ORDER, NOTHING_TO_REFUND, REFUNDED, MANUAL }

    /**
     * 경보가 {@code increase()} 로 보는 계수기를 기동 때 0 으로 만든다(B-101) — 처음 늘 때 생기면 첫 값이 이미 1 이라 increase() 가
     * 첫 사고를 0 으로 본다(2026-10-22 실습: FAILED 3건에도 OrderOutboxRowFailed 가 울리지 않았다).
     * [JA] 警報が increase() で見るカウンターを起動時に 0 で作る — 初回の増加で生まれると最初の値が 1 で、increase() が初回を 0 と見る。
     */
    @jakarta.annotation.PostConstruct
    void registerAlertCounters() {
        for (Outcome outcome : Outcome.values()) {
            Metrics.counter(METRIC, "outcome", outcome.name().toLowerCase());
        }
    }

    /** 부족한 줄 — catalog 가 알린 옵션과 수. */
    public record Line(Long productId, Long colorId, String size, int shortQty) {
    }

    private final Recorder recorder;
    private final OrderService orderService;

    public OrderStockShortageService(Recorder recorder, OrderService orderService) {
        this.recorder = recorder;
        this.orderService = orderService;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Outcome apply(long orderId, String eventId, List<Line> lines) {
        Recorded r = recorder.record(orderId, eventId, lines);
        Outcome outcome = decideAndRefund(orderId, r);
        Metrics.counter(METRIC, "outcome", outcome.name().toLowerCase()).increment();
        return outcome;
    }

    private Outcome decideAndRefund(long orderId, Recorded r) {
        if (r.unknownOrder()) {
            log.error("재고 부족 알림의 주문을 찾을 수 없다 — 수동 확인 필요. orderId={}", orderId);
            return Outcome.UNKNOWN_ORDER;
        }
        if (r.resolved()) {
            return Outcome.DUPLICATE;
        }
        if (r.refundItems().isEmpty() || !OrderStatus.PAID_STATUSES.contains(r.status())) {
            // 이미 (손님·운영자가) 환불했거나, 결제가 살아 있지 않다 — 돌려줄 것이 없다.
            recorder.resolve(orderId);
            log.info("재고 부족: 돌려줄 금액이 남아 있지 않다. orderId={} status={}", orderId, r.status());
            return Outcome.NOTHING_TO_REFUND;
        }
        if (r.provider() == PaymentProvider.BANK_TRANSFER) {
            recorder.resolve(orderId);
            log.error("재고 부족 — 무통장입금 주문이라 계좌로 송금해야 한다. 수동 환불 필요. orderId={} 품목 {}개 {}개",
                    orderId, r.refundItems().size(), units(r.refundItems()));
            return Outcome.MANUAL;
        }
        try {
            orderService.refundOrder(r.trackingId(), null, true, REFUND_REASON, r.refundItems());
        } catch (RuntimeException e) {
            log.error("재고 부족 자동 환불 실패 — 수동 환불 필요(다시 전달되면 다시 시도한다). orderId={} 품목 {}개 {}개: {}",
                    orderId, r.refundItems().size(), units(r.refundItems()), e.toString());
            throw e;
        }
        recorder.resolve(orderId);
        log.warn("재고 부족 자동 환불: orderId={} 품목 {}개 {}개", orderId, r.refundItems().size(), units(r.refundItems()));
        return Outcome.REFUNDED;
    }

    private static int units(List<OrderItemQtyRequest> items) {
        return items.stream().mapToInt(OrderItemQtyRequest::getQty).sum();
    }

    /** 적은 결과 — 환불에 필요한 것만(트랜잭션 밖으로 엔티티를 들고 나가지 않는다). */
    public record Recorded(boolean unknownOrder, boolean resolved, String trackingId, OrderStatus status,
            PaymentProvider provider, List<OrderItemQtyRequest> refundItems) {
        static Recorded unknown() {
            return new Recorded(true, false, null, null, null, List.of());
        }
    }

    /**
     * 적는 쪽 — 자기 트랜잭션. 환불({@link OrderService#refundOrder})은 트랜잭션 밖에서 불러야 한다(보상 이벤트 발행이
     * 그 뒤에 따른다), 그래서 별도 빈이다.
     */
    @Service
    public static class Recorder {

        private final OrderRepository orderRepository;
        private final OrderCacheEvictor cacheEvictor;

        public Recorder(OrderRepository orderRepository, OrderCacheEvictor cacheEvictor) {
            this.orderRepository = orderRepository;
            this.cacheEvictor = cacheEvictor;
        }

        @Transactional
        public Recorded record(long orderId, String eventId, List<Line> lines) {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null) {
                return Recorded.unknown();
            }
            if (order.getShortageEventId() == null) {
                int unmatched = 0;
                for (Line line : lines) {
                    int remaining = Math.max(0, line.shortQty());
                    for (OrderProduct p : order.getOrderedProducts()) {
                        if (remaining == 0) {
                            break;
                        }
                        if (Objects.equals(p.getProductId(), line.productId())
                                && Objects.equals(p.getColorId(), line.colorId())
                                && Objects.equals(p.getSize(), line.size())) {
                            int add = Math.min(remaining, p.allocatedQty());
                            p.setShortQty(p.getShortQty() + add);
                            remaining -= add;
                        }
                    }
                    unmatched += remaining;
                }
                if (unmatched > 0) {
                    log.error("재고 부족 알림의 {}개가 주문 품목과 맞지 않는다 — 수동 확인 필요. orderId={}", unmatched, orderId);
                }
                order.setShortageEventId(eventId);
                orderRepository.saveAndFlush(order);
                cacheEvictor.evictOrderCaches(orderId, "stockShortage");
            } else if (!order.getShortageEventId().equals(eventId)) {
                // 차감은 주문당 한 번이라 부족 알림도 하나다. 다른 키가 오면 발행 쪽이 바뀐 것이다.
                log.error("주문에 이미 다른 재고 부족 기록이 있다({} ≠ {}) — 수동 확인 필요. orderId={}",
                        order.getShortageEventId(), eventId, orderId);
            }

            // 돌려줄 수 = 적힌 부족분 − 이미 환불된 수(환불은 부족분을 먼저 소진한다). 재시도해도 두 번 돌려주지 않는다.
            List<OrderItemQtyRequest> items = new ArrayList<>();
            for (OrderProduct p : order.getOrderedProducts()) {
                int toRefund = Math.min(Math.max(0, p.getShortQty() - p.getRefundedQty()), p.remainingToRefund());
                if (toRefund > 0) {
                    OrderItemQtyRequest item = new OrderItemQtyRequest();
                    item.setOrderProductId(p.getOrderProductId());
                    item.setQty(toRefund);
                    items.add(item);
                }
            }
            return new Recorded(false, order.getShortageResolvedAt() != null, order.getTrackingId(),
                    order.getOrderStatus(), order.getPaymentProvider(), List.copyOf(items));
        }

        @Transactional
        public void resolve(long orderId) {
            orderRepository.findById(orderId).ifPresent(o -> {
                o.setShortageResolvedAt(LocalDateTime.now());
                orderRepository.save(o);
            });
        }
    }
}
