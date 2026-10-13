package com.project.order_service.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.project.common.constants.OrderStatus;
import com.project.order_service.domain.exception.OrderDomainException;
import com.project.order_service.domain.exception.OrderNotFoundException;
import com.project.order_service.model.Order;
import com.project.order_service.ports.output.message.publisher.OrderCompletedKafkaPublisher;
import com.project.order_service.repository.jpa.OrderRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 구매 확정 — {@code DELIVERED → COMPLETED}, 주문의 마지막 전이 (docs/25 §12).
 *
 * <h3>비어 있던 자리</h3>
 *
 * <p>{@code OrderStatus.COMPLETED} 는 선언만 있고 <b>그 값을 넣는 코드가 저장소 어디에도
 * 없었다.</b> PROCESSING·DISPATCHED·DELIVERED 가 비어 있던 것({@code OrderDeliveryStatusService})
 * 과 같은 모양이다. 배달 완료가 끝이었고, 주문은 거기서 영원히 멈췄다.
 *
 * <h3>두 갈래</h3>
 *
 * <ul>
 *   <li><b>손님이 누른다</b> — {@link #confirmByCustomer}. 배달 완료된 <b>자기</b> 주문만.
 *       남의 주문이면 403 이 아니라 <b>404</b> 다(번호를 훑어 존재 여부를 알아내지 못하게,
 *       {@code OrderDataService.getOrder} 와 같은 규칙).</li>
 *   <li><b>기한이 지나면 자동으로</b> — {@link #autoConfirmSweep}. 배달 완료 뒤
 *       {@code order.auto-confirm.days}(기본 7일)가 지난 주문을 한 시간마다 훑는다. 손님이
 *       아무것도 누르지 않는 경우가 대부분이라 이쪽이 실제 다수다. {@code autoCompleted} 를
 *       참으로 남겨 화면이 "자동으로 확정되었습니다" 라고 말할 수 있게 한다.</li>
 * </ul>
 *
 * <h3>확정이 바꾸는 것</h3>
 *
 * <p>주문 상태와 시각뿐이다. 환불은 확정 뒤에도 관리자 경로(반품 입고)로 열려 있다 —
 * {@code SHIPPED_STATUSES} 에 COMPLETED 가 들어 있다. 손님의 직접 환불은 배달 완료 시점에
 * 이미 닫혀 있으므로({@code SELF_SERVICE_REFUNDABLE}) 확정이 새로 닫는 문은 없다. 즉
 * 확정은 <b>"받았고 이상 없다" 는 손님의 말</b>이고, 매출 집계({@code PAID_STATUSES})에는
 * 배달 완료와 똑같이 잡힌다.
 *
 * <h3>멱등</h3>
 *
 * <p>이미 COMPLETED 면 그대로 돌려준다 — 두 번 누르거나, 자동 확정과 손님의 클릭이 겹쳐도
 * 두 번째는 아무 일도 하지 않는다. 이벤트도 커밋 뒤에만 나간다.
 */
@Slf4j
@Service
public class OrderPurchaseConfirmationService {

    private final OrderRepository orderRepository;
    private final OrderCacheEvictor cacheEvictor;
    private final OrderCompletedKafkaPublisher publisher;

    /** 상태 변경 + 이벤트(docs/76). 세터 — 생성자를 쓰는 테스트가 많다. 기본값은 이벤트 없이 상태만 바꾼다(단위 테스트). */
    // [JA] 状態変更 + イベント(docs/76)。セッター — コンストラクタを使うテストが多い。デフォルトはイベントなしで状態だけ変える。
    private OrderStatusChanger statusChanger = OrderStatusChanger.withoutEvents();

    @org.springframework.beans.factory.annotation.Autowired
    public void setStatusChanger(OrderStatusChanger statusChanger) {
        this.statusChanger = statusChanger;
    }

    private final boolean autoEnabled;
    private final int autoDays;
    private final int batchSize;

    public OrderPurchaseConfirmationService(
            OrderRepository orderRepository,
            OrderCacheEvictor cacheEvictor,
            OrderCompletedKafkaPublisher publisher,
            @Value("${order.auto-confirm.enabled:true}") boolean autoEnabled,
            @Value("${order.auto-confirm.days:7}") int autoDays,
            @Value("${order.auto-confirm.batch-size:200}") int batchSize) {
        this.orderRepository = orderRepository;
        this.cacheEvictor = cacheEvictor;
        this.publisher = publisher;
        this.autoEnabled = autoEnabled;
        this.autoDays = Math.max(0, autoDays);
        this.batchSize = batchSize <= 0 ? 200 : batchSize;
    }

    /** 자동 확정까지의 날 수 — 화면이 "N일 뒤 자동 확정" 이라고 말할 때 쓴다. */
    public int autoConfirmDays() {
        return autoDays;
    }

    public boolean autoConfirmEnabled() {
        return autoEnabled;
    }

    /**
     * 손님이 구매를 확정한다.
     *
     * @throws OrderNotFoundException 주문이 없거나 요청자의 것이 아니다 → 404
     * @throws OrderDomainException   배달 완료 상태가 아니다 → 400
     */
    @Transactional
    public Order confirmByCustomer(Long orderId, Long requesterId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null || requesterId == null || !requesterId.equals(order.getCustomerId())) {
            log.warn("남의 주문 구매 확정 시도를 차단: orderId={}", orderId);
            throw new OrderNotFoundException("주문을 찾을 수 없습니다.");
        }
        if (order.getOrderStatus() == OrderStatus.COMPLETED) {
            return order;   // 이미 확정됐다 — 두 번 눌렀거나 자동 확정과 겹쳤다.
        }
        if (order.getOrderStatus() != OrderStatus.DELIVERED) {
            throw new OrderDomainException(
                    "배송이 완료된 주문만 구매 확정할 수 있습니다: status=" + order.getOrderStatus());
        }
        complete(order, false, LocalDateTime.now(ZoneOffset.UTC));
        return order;
    }

    /**
     * 기한이 지난 배달 완료 주문을 자동 확정한다. 한 시간마다. 기동 직후 2분은 기다린다.
     *
     * <p>한 번에 {@code batch-size} 건까지다 — 밀린 주문이 많아도 한 트랜잭션이 커지지 않게.
     * 남은 것은 다음 주기에 이어서 한다.
     */
    @Scheduled(fixedDelayString = "${order.auto-confirm.check-ms:3600000}", initialDelay = 120000)
    @Transactional
    public void autoConfirmSweep() {
        int done = autoConfirmDue(LocalDateTime.now(ZoneOffset.UTC));
        if (done > 0) {
            log.info("배달 완료 뒤 {}일이 지나 {}건을 자동 구매 확정", autoDays, done);
        }
    }

    /**
     * @param now 기준 시각(UTC). {@code now − days} 이전에 배달 완료된 주문이 대상이다.
     * @return 확정한 건수
     */
    int autoConfirmDue(LocalDateTime now) {
        if (!autoEnabled) {
            return 0;
        }
        LocalDateTime cutoff = now.minusDays(autoDays);
        List<Order> due = orderRepository.findByOrderStatusAndDeliveredCreatedAtBefore(
                OrderStatus.DELIVERED, cutoff, PageRequest.of(0, batchSize));
        for (Order order : due) {
            complete(order, true, now);
        }
        return due.size();
    }

    private void complete(Order order, boolean auto, LocalDateTime at) {
        OrderStatus before = order.getOrderStatus();
        statusChanger.change(order, OrderStatus.COMPLETED, auto ? "auto-confirm" : "customer");
        order.setCompletedAt(at);
        order.setAutoCompleted(auto);
        orderRepository.save(order);
        cacheEvictor.evictOrderCaches(order.getOrderId(), auto ? "auto-complete" : "customer-complete");
        log.info("구매 확정: orderId={} {} → COMPLETED auto={}", order.getOrderId(), before, auto);
        afterCommit(() -> publisher.publish(order));
    }

    /** 트랜잭션 안이면 커밋 뒤에, 아니면(테스트) 바로. 커밋이 안 됐는데 알림만 나가면 안 된다. */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /** 컨트롤러가 응답에 넣을 값 — 없으면 비어 있다. */
    public static Optional<LocalDateTime> completedAt(Order order) {
        return Optional.ofNullable(order.getCompletedAt());
    }
}
