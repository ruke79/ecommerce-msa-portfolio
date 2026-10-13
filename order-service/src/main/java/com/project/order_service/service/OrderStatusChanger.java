package com.project.order_service.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.project.common.constants.OrderStatus;
import com.project.order_service.model.Order;
import com.project.order_service.outbox.PaymentOutboxHelper;
import com.project.common.outbox.OutboxStatus;

import lombok.extern.slf4j.Slf4j;

/**
 * 주문 상태를 바꾸는 <b>유일한</b> 자리(docs/76) — 상태를 쓰고, 같은 트랜잭션에 "바뀌었다" 를 아웃박스에 남긴다.
 *
 * <p>예전에는 열 군데가 저마다 {@code order.setOrderStatus(...)} 를 불렀다. 상태는 DB 에만 남고 <b>언제 · 무엇이
 * 바꿨는지</b>는 로그 한 줄뿐이었다 — 주문 진행 화면은 order 의 DB 를 다시 읽는 수밖에 없었다(CQRS 가 아니다).
 * 이제 상태 변경은 {@code order-status} 이벤트가 되어 saga-projection 이 읽기 모델(OrderTimeline)로 접는다.
 *
 * <p>규칙:
 * <ul>
 *   <li>아웃박스 행은 상태를 쓴 트랜잭션 안에서 함께 들어간다 — 둘 중 하나만 커밋되는 일이 없다(트랜잭셔널 아웃박스).
 *       호출자는 반드시 트랜잭션 안에 있어야 한다(기존 호출부는 모두 {@code @Transactional} 메서드다).</li>
 *   <li>같은 상태로 다시 "바꾸는" 것은 이벤트가 아니다 — 아웃박스에 남기지 않는다(재전달 · 멱등 경로).</li>
 *   <li>이벤트에는 상태 · 출처 · 시각만. 토큰 · 이메일 · 주소는 없다.</li>
 *   <li>가드 B-78: {@code setOrderStatus(} · {@code .markPaid(} 는 이 클래스 밖에서 부르지 않는다.</li>
 * </ul>
 *
 * <p>서비스들은 생성자가 아니라 세터로 받는다({@code OrderSaga.setStockHold} 와 같은 이유 — 생성자를 쓰는 테스트가
 * 많다). 세터가 불리기 전의 기본값은 {@link #withoutEvents()} 라 단위 테스트는 상태만 바뀌고 아웃박스는 없다.
 * Spring 에서는 {@code @Autowired} 세터가 필수라 빈이 없으면 기동이 실패한다 — 운영에서 조용히 빠지는 일은 없다.
 *
 * <p>[JA] 注文状態を変える<b>唯一の</b>場所(docs/76) — 状態を書き、同じトランザクションで「変わった」をアウトボックスに残す。
 * 以前は十箇所がそれぞれ {@code setOrderStatus} を呼び、状態は DB にだけ残って<b>いつ・何が変えたか</b>はログ一行だけだった。
 * 今は状態変更が {@code order-status} イベントとなり、saga-projection が読み取りモデル(OrderTimeline)に畳み込む。
 * 同じ状態への「変更」はイベントではない(再配信・冪等経路)。ガード B-78: {@code setOrderStatus(}・{@code .markPaid(} は
 * このクラスの外では呼ばない。
 */
@Slf4j
@Component
public class OrderStatusChanger {

    /** 아웃박스 행에 남기는 내용 — 릴레이가 Avro {@code OrderStatusChanged} 로 바꿔 보낸다(eventId 는 행 id). */
    public record Payload(long orderId, Long customerId, String previousStatus, String status, String source,
            Instant occurredAt) {
    }

    /** 이벤트를 남기는 쪽. 운영은 아웃박스, 테스트 기본값은 아무것도 안 함. */
    @FunctionalInterface
    public interface Recorder {
        void record(Payload payload);
    }

    private final Recorder recorder;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public OrderStatusChanger(PaymentOutboxHelper outbox) {
        this(p -> outbox.saveOrderStatusOutbox(p, OutboxStatus.STARTED), Clock.systemUTC());
    }

    OrderStatusChanger(Recorder recorder, Clock clock) {
        this.recorder = recorder;
        this.clock = clock;
    }

    /** 이벤트를 남기지 않는 변경기 — 세터 주입 전의 기본값(단위 테스트). */
    public static OrderStatusChanger withoutEvents() {
        return new OrderStatusChanger(p -> { }, Clock.systemUTC());
    }

    /**
     * 상태를 바꾼다. 같은 상태면 아무것도 하지 않는다(이벤트도 없다).
     *
     * @param source 무엇이 바꿨나 — payment-saga · delivery · customer · admin · bank-transfer · auto-confirm · expiry …
     * @return 실제로 바뀌었으면 true
     */
    public boolean change(Order order, OrderStatus to, String source) {
        OrderStatus from = order.getOrderStatus();
        if (from == to) {
            return false;
        }
        order.setOrderStatus(to);
        record(order, from, source);
        return true;
    }

    /** 결제 확인 — 상태 PAID 와 결제 시각을 함께({@link Order#markPaid}). 이미 PAID 면 이벤트 없음. */
    public boolean markPaid(Order order, LocalDateTime at, String source) {
        OrderStatus from = order.getOrderStatus();
        order.markPaid(at);
        if (from == OrderStatus.PAID) {
            return false;
        }
        record(order, from, source);
        return true;
    }

    /** 주문이 생겼다 — 이전 상태 없음. 타임라인의 첫 항목이다. 저장 뒤(주문 id 가 있어야 한다) 부른다. */
    public void created(Order order) {
        record(order, null, "created");
    }

    private void record(Order order, OrderStatus from, String source) {
        if (order.getOrderId() == null) {
            // 저장 전 주문에는 id 가 없다 — 이벤트를 남길 수 없다. 호출 순서의 버그라 조용히 넘기지 않는다.
            throw new IllegalStateException("주문 id 가 없어 상태 변경 이벤트를 남길 수 없다 — 저장 뒤에 바꿀 것: " + source);
        }
        Payload payload = new Payload(order.getOrderId(), order.getCustomerId(),
                from == null ? null : from.name(), order.getOrderStatus().name(), source, clock.instant());
        recorder.record(payload);
        log.info("주문 상태 변경: orderId={} {} → {} ({})", payload.orderId(), payload.previousStatus(), payload.status(), source);
    }
}
