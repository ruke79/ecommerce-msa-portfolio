package com.project.order_service.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.common.constants.OrderStatus;
import com.project.order_service.model.Order;
import com.project.order_service.repository.jpa.OrderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 배송 상태({@code delivery-status} 토픽)를 주문 상태에 반영한다 — 사가의 마지막 마디.
 *
 * <p>{@code OrderStatus} 의 PROCESSING·DISPATCHED·DELIVERED 는 선언만 있고 아무도 채우지
 * 않던 값이었다(docs/25 §0). 주문은 PAID 에서 멈췄고, 프론트의 "발송됨" 필터는 영원히
 * 비어 있었으며, {@code deliveredCreatedAt} 은 언제나 null 이었다. 여기서 채운다.
 *
 * <h3>규칙 — 앞으로만 간다</h3>
 *
 * <p>Kafka 는 순서를 파티션 안에서만 지키고 재전달도 한다. 배달 완료 뒤에 늦게 온 "이동 중"
 * 이 주문을 되돌리면 손님은 받은 물건이 다시 오는 중이라고 보게 된다. 그래서 전이는
 * <b>PAID → PROCESSING → DISPATCHED → DELIVERED</b> 한 방향이고, 같은 자리나 뒤로 가는
 * 이벤트는 무시한다.
 *
 * <p><b>결제된 주문만</b> 움직인다. 환불·취소된 주문({@code REFUNDED}/{@code CANCELED})에
 * 배송 이벤트가 늦게 도착해도 — 발송 뒤 환불된 물건이 그래도 배달되는 경우가 실제로
 * 있다 — 주문 상태는 돈의 상태를 말하므로 덮어쓰지 않는다. {@code COMPLETED}(구매 확정)도
 * 그대로 둔다.
 *
 * <h3>배송 상태 → 주문 상태</h3>
 *
 * <ul>
 *   <li>{@code PENDING}(배송 건 생성) → {@code PROCESSING}</li>
 *   <li>송장 등록 이후 이동 중인 모든 상태(INFO_RECEIVED·PICKED_UP·IN_TRANSIT·통관·배달
 *       출발·배달 실패·예외) → {@code DISPATCHED}</li>
 *   <li>{@code DELIVERED} → {@code DELIVERED} + {@code deliveredCreatedAt}</li>
 *   <li>{@code CANCELLED} → <b>{@code PAID} 로 돌아간다</b>(PROCESSING·DISPATCHED 였다면).
 *       배송 취소는 발송 <b>전</b>에만 가능하므로 물건은 나가지 않았다 — 그런데 주문이
 *       DISPATCHED 로 남으면 "배송이 시작된 주문은 환불할 수 없습니다" 에 갇힌다. 손님이 배송을
 *       취소한 이유가 환불인데 그 길이 막히는 것이다. 실제로 그랬다.</li>
 *   <li>{@code RETURNED} → 주문은 손대지 않는다. 반송은 반품 입고가 처리한다.</li>
 * </ul>
 *
 * <p>"앞으로만 간다" 의 유일한 예외가 {@code CANCELLED} 다. 결제 취소·환불로 시스템이 접은
 * 배송이면 주문은 이미 {@code REFUNDED}/{@code CANCELED} 라 {@link #MOVABLE} 밖이고, 그때는
 * 여기서도 손대지 않는다 — 돈의 상태가 우선이다.
 *
 * <p>모르는 상태는 발행자가 함께 보내는 {@code stage} 로 판정한다 — 배송 서비스가 상태를
 * 하나 더 만들어도 여기가 깨지지 않게.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderDeliveryStatusService {

    /** 상태 변경 + 이벤트(docs/76). 세터 — 생성자를 쓰는 테스트가 많다. 기본값은 이벤트 없이 상태만 바꾼다(단위 테스트). */
    // [JA] 状態変更 + イベント(docs/76)。セッター — コンストラクタを使うテストが多い。デフォルトはイベントなしで状態だけ変える。
    private OrderStatusChanger statusChanger = OrderStatusChanger.withoutEvents();

    @org.springframework.beans.factory.annotation.Autowired
    public void setStatusChanger(OrderStatusChanger statusChanger) {
        this.statusChanger = statusChanger;
    }

    /** 배송 이벤트가 움직일 수 있는 주문 상태. 이 밖의 주문은 돈의 상태가 우선이다. */
    static final Set<OrderStatus> MOVABLE = EnumSet.of(OrderStatus.PAID, OrderStatus.PROCESSING, OrderStatus.DISPATCHED);

    private final OrderRepository orderRepository;
    private final OrderCacheEvictor cacheEvictor;

    /**
     * @return 주문 상태가 실제로 바뀌었으면 true
     */
    @Transactional
    public boolean apply(Long orderId, String deliveryStatus, String stage) {
        if ("CANCELLED".equalsIgnoreCase(deliveryStatus == null ? "" : deliveryStatus.trim())) {
            return revertToPaidOnCancel(orderId);
        }
        Optional<OrderStatus> target = targetOf(deliveryStatus, stage);
        if (target.isEmpty()) {
            log.debug("주문 상태와 무관한 배송 상태, 무시: orderId={} status={} stage={}", orderId, deliveryStatus, stage);
            return false;
        }

        Optional<Order> found = orderRepository.findById(orderId);
        if (found.isEmpty()) {
            // 배송 서비스가 아는 주문을 우리가 모른다 — 삭제됐거나 다른 환경의 이벤트다. 재시도해도 같다.
            log.warn("배송 상태를 받았지만 주문이 없음: orderId={} status={}", orderId, deliveryStatus);
            return false;
        }
        Order order = found.get();
        OrderStatus current = order.getOrderStatus();

        if (current == null || !MOVABLE.contains(current)) {
            log.info("결제 상태가 우선이라 배송 상태를 반영하지 않음: orderId={} order={} delivery={}",
                    orderId, current, deliveryStatus);
            return false;
        }
        if (!isForward(current, target.get())) {
            log.debug("뒤로 가는 배송 이벤트, 무시: orderId={} {} → {}", orderId, current, target.get());
            return false;
        }

        statusChanger.change(order, target.get(), "delivery");
        if (target.get() == OrderStatus.DELIVERED && order.getDeliveredCreatedAt() == null) {
            order.setDeliveredCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        }
        orderRepository.save(order);
        cacheEvictor.evictOrderCaches(orderId, "delivery→" + target.get());
        log.info("배송 상태 반영: orderId={} {} → {} (delivery={})", orderId, current, target.get(), deliveryStatus);
        return true;
    }

    /**
     * 발송 전 배송 취소 → 주문을 {@code PAID} 로 되돌린다.
     *
     * <p>{@code PROCESSING}(배송 준비) 이나 {@code DISPATCHED}(송장 등록) 였을 때만이다.
     * 배송 도메인이 발송 뒤의 취소를 막으므로 {@code DELIVERED} 에서 이 이벤트가 오는 일은
     * 없지만, 온다 해도 되돌리지 않는다 — 받은 물건을 "결제만 된 상태" 로 만들 수는 없다.
     */
    private boolean revertToPaidOnCancel(Long orderId) {
        Optional<Order> found = orderRepository.findById(orderId);
        if (found.isEmpty()) {
            log.warn("배송 취소를 받았지만 주문이 없음: orderId={}", orderId);
            return false;
        }
        Order order = found.get();
        OrderStatus current = order.getOrderStatus();
        if (current != OrderStatus.PROCESSING && current != OrderStatus.DISPATCHED) {
            log.info("배송 취소 — 주문은 그대로 둔다: orderId={} order={}", orderId, current);
            return false;
        }
        statusChanger.change(order, OrderStatus.PAID, "delivery-cancelled");
        orderRepository.save(order);
        cacheEvictor.evictOrderCaches(orderId, "delivery→CANCELLED");
        log.info("배송 취소로 주문을 되돌림: orderId={} {} → PAID (환불 가능)", orderId, current);
        return true;
    }

    /** 배송 상태 → 주문 상태. 주문을 움직이지 않는 상태면 비어 있다. */
    static Optional<OrderStatus> targetOf(String deliveryStatus, String stage) {
        String status = deliveryStatus == null ? "" : deliveryStatus.trim().toUpperCase();
        switch (status) {
            case "PENDING":
                return Optional.of(OrderStatus.PROCESSING);
            case "INFO_RECEIVED":
            case "PICKED_UP":
            case "IN_TRANSIT":
            case "CUSTOMS_HOLD":
            case "CUSTOMS_CLEARED":
            case "OUT_FOR_DELIVERY":
            case "ATTEMPT_FAILED":
            case "EXCEPTION":
                return Optional.of(OrderStatus.DISPATCHED);
            case "DELIVERED":
                return Optional.of(OrderStatus.DELIVERED);
            case "CANCELLED":
            case "RETURNED":
                return Optional.empty();
            default:
                break;
        }
        // 모르는 상태 — 단계로 판정한다.
        String s = stage == null ? "" : stage.trim().toUpperCase();
        return switch (s) {
            case "ORDERED" -> Optional.of(OrderStatus.PROCESSING);
            case "SHIPPED", "IN_TRANSIT", "CUSTOMS", "EXCEPTION" -> Optional.of(OrderStatus.DISPATCHED);
            case "DELIVERED" -> Optional.of(OrderStatus.DELIVERED);
            default -> Optional.empty();
        };
    }

    /** PAID → PROCESSING → DISPATCHED → DELIVERED 순서에서 앞으로 가는가. */
    private static boolean isForward(OrderStatus from, OrderStatus to) {
        return rank(to) > rank(from);
    }

    private static int rank(OrderStatus s) {
        return switch (s) {
            case PAID -> 0;
            case PROCESSING -> 1;
            case DISPATCHED -> 2;
            case DELIVERED -> 3;
            default -> Integer.MAX_VALUE;
        };
    }
}
