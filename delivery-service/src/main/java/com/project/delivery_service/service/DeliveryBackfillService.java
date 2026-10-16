package com.project.delivery_service.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.project.common.dto.order.OrderShippingDto;
import com.project.delivery_service.client.OrderServiceClient;
import com.project.delivery_service.domain.Delivery;
import com.project.delivery_service.dto.CreateDeliveryCommand;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제는 됐는데 배송이 없는 주문을 <b>손으로 이어 준다</b> — 관리자 복구용.
 *
 * <h3>왜 필요한가</h3>
 *
 * <p>배송은 결제 이벤트가 만든다. 그런데 그 경로가 막혀 있던 동안 결제된 주문들이 배송 없이
 * 남았다(docs/25 §11.5 — 나라 이름이 {@code varchar(2)} 컬럼을 넘겨 결제 레코드가 DLT 로
 * 갔다). Kafka 레코드는 이미 DLT 에 있어 다시 오지 않고, 그 주문은 새 결제도 일어나지 않는다.
 * 즉 <b>고칠 수 있는 길이 코드 안에 없었다.</b>
 *
 * <p>버그를 고쳤다고 그 주문들이 살아나지는 않는다. 이 자리가 그 간극을 메운다.
 *
 * <p>주소와 품목은 {@link OrderServiceClient} 로 <b>주문에서 다시 읽는다</b>. 관리자가
 * 본문으로 넘기게 하지 않는 이유는 분명하다 — 그러면 배송지를 임의로 지정할 수 있고,
 * 그것은 남의 물건을 자기 주소로 보내는 길이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryBackfillService {

    private final DeliveryService deliveryService;
    private final OrderServiceClient orderServiceClient;

    /**
     * @return 새로 만든 배송. 이미 있거나 주문에서 배송지를 읽지 못하면 비어 있음
     */
    public Optional<Delivery> createFromOrder(Long orderId) {
        OrderShippingDto order;
        try {
            order = orderServiceClient.shipping(orderId);
        } catch (Exception e) {
            log.warn("복구: 주문 {} 의 배송 정보를 읽지 못했다: {}", orderId, e.toString());
            return Optional.empty();
        }
        if (order == null || order.address() == null
                || order.address().line1() == null || order.address().line1().isBlank()) {
            log.warn("복구: 주문 {} 에 배송지가 없어 배송을 만들 수 없다", orderId);
            return Optional.empty();
        }

        // 결제됐고 아직 배송 전인 주문만. 🐛 예전에는 orderStatus 를 읽지 않아 미결제 · 환불된 주문 번호로도 배송이 생겼다 —
        // "송장 대기" 목록에 올라 실제로 출고될 수 있었다.
        // [JA] 決済済みでまだ配送前の注文だけ。以前は orderStatus を読まず、未決済・返金済みの注文にも配送が作られていた。
        if (!"PAID".equals(order.orderStatus()) && !"PROCESSING".equals(order.orderStatus())) {
            log.warn("복구: 주문 {} 은 결제됐고 배송 전인 주문이 아니다(상태 {}) — 배송을 만들지 않는다", orderId, order.orderStatus());
            return Optional.empty();
        }

        OrderShippingDto.Address a = order.address();
        List<CreateDeliveryCommand.Item> items = new ArrayList<>();
        if (order.items() != null) {
            for (OrderShippingDto.Item item : order.items()) {
                items.add(new CreateDeliveryCommand.Item(item.orderProductId(), item.productId(),
                        item.name(), item.image(), Math.max(1, item.quantity()), item.skuCode()));
            }
        }

        Optional<Delivery> created = deliveryService.createFromOrder(new CreateDeliveryCommand(
                orderId, order.customerId(), null,
                a.recipientName(), a.recipientPhone(), a.postalCode(),
                a.line1(), a.line2(), a.city(), a.state(), a.country(), null, items));

        created.ifPresent(d -> log.info("복구: 주문 {} 의 배송을 손으로 만들었다 — deliveryId={}",
                orderId, d.getDeliveryId()));
        return created;
    }
}
