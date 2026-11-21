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
 *
 * <p>[JA] 在庫不足の補償 — 決済されたが送れない数量を記録して自動返金する(docs/53 §過剰販売)。
 *
 * <h3>🔴 なぜ必要か</h3>
 *
 * <p>在庫は<b>決済後に</b>差し引く。最後の 1 個を二人が同時に決済すると、後の差し引きが足りない。以前は catalog がログを
 * 1 行残すだけだった — 注文は決済完了で進み、返金も警報もなかった。客は代金を払ったのに品物はなく、
 * 運用者は配送段階で(または客の問い合わせで)ようやく知った。
 *
 * <p>今は catalog が不足数を {@code stock-shortage} で知らせ(アウトボックス)、ここで: ①
 * <b>記録する</b> — 行ごとに {@code shortQty}。在庫復元・返品・配送数量から除かれる(差し引かれたことがない)。
 * 冪等キーは注文の {@code shortageEventId} — 再配信は捨てる。② <b>返す</b> —
 * その数だけ部分返金(管理者権限の既存返金経路 — 金額・通貨・プロバイダーのルールがそのまま)。客は既存の返金通知を受け取る。
 * 全部足りなければ注文全体が返金され、決済キャンセルが配送もキャンセルする。③ <b>返せなければ引き継ぐ</b> —
 * 銀行振込は口座へ送金する人が要る。ログ「手動返金が必要」が Loki 警報 {@code ManualActionRequired} に行く。
 *
 * <p>返金が失敗したら例外を投げる — Kafka が再配信し、その時は記録段階を飛ばして(既に記録した)返金だけを再試行する。
 * 返金数量は「記録された不足分 − 既に返金した数」なので二重返金しない。プロバイダー側は返金の冪等キーが同じリクエストを一回にまとめる。
 * 再試行が全部失敗すると DLT に行き、毎回残した「手動返金が必要」で運用者が知る。
 */
@Slf4j
@Service
public class OrderStockShortageService {

    static final String METRIC = "order.stock.shortage";
    public static final String REFUND_REASON = "재고 부족 — 보낼 수 없는 수량을 자동 환불했습니다";

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
    // [JA] 不足した行 — catalog が知らせたオプションと数。
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
            // [JA] 既に(客・運用者が)返金したか、決済が生きていない — 返すものがない。
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
    // [JA] 記録した結果 — 返金に必要なものだけ(トランザクション外へエンティティを持ち出さない)。
    public record Recorded(boolean unknownOrder, boolean resolved, String trackingId, OrderStatus status,
            PaymentProvider provider, List<OrderItemQtyRequest> refundItems) {
        static Recorded unknown() {
            return new Recorded(true, false, null, null, null, List.of());
        }
    }

    /**
     * 적는 쪽 — 자기 트랜잭션. 환불({@link OrderService#refundOrder})은 트랜잭션 밖에서 불러야 한다(보상 이벤트 발행이
     * 그 뒤에 따른다), 그래서 별도 빈이다.
     *
     * <p>[JA] 記録する側 — 自前のトランザクション。返金({@link OrderService#refundOrder})
     * はトランザクション外で呼ぶ必要がある(補償イベントの発行がその後に続く)ので、別 Bean である。
     */
    @Service
    public static class Recorder {

        private final OrderRepository orderRepository;
        private final OrderCacheEvictor cacheEvictor;
        private final com.project.order_service.outbox.PaymentOutboxHelper outbox;

        public Recorder(OrderRepository orderRepository, OrderCacheEvictor cacheEvictor,
                com.project.order_service.outbox.PaymentOutboxHelper outbox) {
            this.orderRepository = orderRepository;
            this.cacheEvictor = cacheEvictor;
            this.outbox = outbox;
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
                // 5단계 15 — 배송이 이 기록보다 먼저 만들어졌으면 주문 수량 그대로다. 같은 트랜잭션의 아웃박스 행으로 delivery 에
                // 다시 맞추라고 알린다(무통장 · 환불 실패에도 — 보내지 않을 물건이다). delivery 는 주문의 지금 값(배정된 수)을 다시 읽는다.
                // [JA] 配送がこの記録より先に作られていれば注文数量のままだ。同じトランザクションのアウトボックス行で delivery に知らせる。
                outbox.saveEventOutbox(com.project.order_service.outbox.PaymentOutboxHelper.DELIVERY_RESYNC_EVENT, String.valueOf(orderId),
                        java.util.Map.of("orderId", orderId, "eventId", "delivery-resync:" + eventId));
            } else if (!order.getShortageEventId().equals(eventId)) {
                // 차감은 주문당 한 번이라 부족 알림도 하나다. 다른 키가 오면 발행 쪽이 바뀐 것이다.
                // [JA] 差し引きは注文ごとに一度なので不足通知も一つである。別のキーが来たら発行側が変わったということだ。
                log.error("주문에 이미 다른 재고 부족 기록이 있다({} ≠ {}) — 수동 확인 필요. orderId={}",
                        order.getShortageEventId(), eventId, orderId);
            }

            // 돌려줄 수 = 적힌 부족분 − 이미 환불된 수(환불은 부족분을 먼저 소진한다). 재시도해도 두 번 돌려주지 않는다.
            // [JA] 返す数 = 記録された不足分 − 既に返金した数(返金は不足分を先に消化する)。再試行しても二重に返さない。
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
