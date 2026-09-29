package com.project.delivery_service.listener;

import java.util.ArrayList;
import java.util.List;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.dto.order.OrderShippingDto;
import com.project.delivery_service.client.OrderServiceClient;
import com.project.delivery_service.dto.CreateDeliveryCommand;
import com.project.delivery_service.service.DeliveryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 완료 → 배송 생성. <b>사가가 끊겨 있던 지점</b>이다.
 *
 * <p>지금까지 주문은 {@code PAID} 에서 멈췄다. {@code OrderStatus} 에 PROCESSING·DISPATCHED·
 * DELIVERED 가 선언돼 있는데 아무도 그 값을 설정하지 않았고, 프론트의 "발송됨" 필터는
 * 영원히 빈 목록이었다(docs/25 §0). 이 리스너가 그 뒤를 잇는다.
 *
 * <h3>🐛 배송이 한 건도 만들어지지 않았다</h3>
 *
 * <p>이전 버전은 결제 이벤트 안에서 {@code shippingAddress} 를 찾았다. 그런데 {@code payment}
 * 토픽의 {@code PaymentResponse} 에는 주소도 품목도 없다 — 결제 도메인의 사실(주문·금액·상태)
 * 뿐이다. 주소를 못 찾으면 예외를 던지도록 돼 있었으니 <b>모든 결제 완료가 DLT 로 갔고</b>
 * 배송은 영원히 404 였다. 주문 상세의 "배송 준비 중" 은 그 404 를 정상으로 읽은 것이다.
 *
 * <p>이제 주문의 주인에게 묻는다({@link OrderServiceClient}). 이벤트에 주소가 실려 오면
 * (다른 발행자·미래 스키마) 그것을 먼저 쓰고, 없으면 order-service 를 읽는다.
 *
 * <p><b>스키마에 관대하게 읽는다.</b> 결제 이벤트의 DTO 를 그대로 의존하면 결제 도메인이
 * 필드를 하나 추가할 때마다 배송 서비스가 역직렬화 실패로 죽는다. 배송에 필요한 것은
 * "누구의, 어느 주문이, 어디로"뿐이므로 필요한 필드만 꺼내 읽는다(notification-service 와 같은 판단).
 *
 * <h3>취소·환불</h3>
 *
 * <p>결제가 {@code CANCELED} 로 오면(환불·취소 — pay-service 는 둘 다 이 상태로 발행한다)
 * 발송 전인 배송을 접는다. 이미 집화됐으면 손댈 수 없다 — 그건 반품 절차고, 주문 쪽의
 * 반품 입고가 처리한다. {@code FAILED} 는 애초에 배송이 없으니 할 일이 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCompletedListener {

    private final DeliveryService deliveryService;
    private final OrderServiceClient orderServiceClient;
    private final ObjectMapper objectMapper;
    /** 레코드 헤더의 traceparent 로 보낸 쪽의 추적을 이어받는다(docs/51 §4h). */
    private final com.project.common.outbox.OutboxTraceContext outboxTraceContext;

    @KafkaListener(id = "delivery-payment", topics = "payment",
                   containerFactory = "deliveryListenerContainerFactory")
    public void onPayment(List<String> messages,
            // 레코드마다 보낸 쪽의 추적을 이어받는다(docs/51 §4h). 배치 리스너에는 컨슈머 추적이 걸리지 않는다.
            @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> kafkaHeaders) {
        for (int i = 0; i < messages.size(); i++) {
            try (com.project.common.outbox.OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
                    com.project.common.outbox.OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive payment")) {
                handle(messages.get(i));
            } catch (Exception e) {
                // 이 레코드만 DLT 로 보내고 나머지 배치는 계속 처리한다(docs/11 §3).
                throw new BatchListenerFailedException("결제→배송 처리 실패", e, i);
            }
        }
    }

    /**
     * 한 레코드. 테스트가 직접 부른다.
     *
     * <p><b>어떤 갈래로 가든 한 줄을 남긴다.</b> 예전에는 "완료가 아닌 결제" 를 조용히
     * 건너뛰었는데, 그러면 배송이 안 생겼을 때 로그에 <b>아무것도 없다</b> — 이벤트를 못 받은
     * 것인지, 받고 건너뛴 것인지, 어디서 실패한 것인지 구분할 방법이 사라진다. 실제로 그
     * 상태로 한참 헤맸다. 결제 이벤트는 주문당 한 건이라 로그가 넘치지도 않는다.
     */
    void handle(String raw) throws Exception {
        JsonNode event = objectMapper.readTree(raw);

        String status = text(event, "paymentStatus");
        Long orderId = number(event, "orderId");
        if (orderId == null) {
            log.warn("결제 이벤트에 orderId 가 없어 배송을 처리하지 못함: {}", abbreviate(raw));
            return;
        }
        log.info("결제 이벤트 수신: orderId={} status={}", orderId, status);

        if (status != null && status.toUpperCase().contains("CANCEL")) {
            log.info("결제 취소·환불 — 발송 전이면 배송을 접는다: orderId={} 취소됨={}",
                    orderId, deliveryService.cancelByOrder(orderId));
            return;
        }
        if (status == null || !status.toUpperCase().contains("COMPLET")) {
            // 실패 결제로는 배송을 만들지 않는다.
            log.info("완료된 결제가 아니라 배송을 만들지 않음: orderId={} status={}", orderId, status);
            return;
        }

        deliveryService.createFromOrder(commandFor(event, orderId));
    }

    /**
     * 이벤트에 주소가 실려 있으면 그대로, 없으면 order-service 에 묻는다.
     *
     * <p>주소가 끝내 없으면 예외 → DLT. 조용히 빈 주소로 만들면 송장을 낼 수 없는 배송이
     * 생기고, 그것은 아무 화면에도 "왜" 가 나오지 않는다.
     */
    private CreateDeliveryCommand commandFor(JsonNode event, Long orderId) {
        JsonNode address = firstOf(event, "shippingAddress", "address", "deliveryAddress");
        Long customerId = number(event, "customerId", "userId");

        if (address != null && customerId != null) {
            return new CreateDeliveryCommand(
                    orderId,
                    customerId,
                    number(event, "reorderedFromOrderId"),
                    addressText(address, "recipientName", "firstname", "name"),
                    addressText(address, "phoneNumber", "recipientPhone", "phone"),
                    addressText(address, "zipCode", "postalCode"),
                    requireLine1(addressText(address, "address1", "addressLine1", "line1"), orderId),
                    addressText(address, "address2", "addressLine2"),
                    addressText(address, "city"),
                    addressText(address, "state"),
                    addressText(address, "country"),
                    addressText(address, "deliveryMemo", "memo"),
                    readItems(event));
        }

        // 여기서 막히면(네트워크·TLS·인가) 그 예외가 곧 원인이다. 부르기 전에 한 줄 남겨
        // "물어보러 갔다가 못 돌아왔다" 를 로그에서 바로 읽을 수 있게 한다.
        log.info("주문에서 배송 정보를 읽는다: orderId={}", orderId);
        OrderShippingDto order = orderServiceClient.shipping(orderId);
        if (order == null || order.address() == null) {
            throw new IllegalStateException(
                    "주문 " + orderId + " 에 배송 주소가 없습니다. 주소 없이 배송을 만들면 송장을 낼 수 없습니다.");
        }
        OrderShippingDto.Address a = order.address();
        List<CreateDeliveryCommand.Item> items = new ArrayList<>();
        if (order.items() != null) {
            for (OrderShippingDto.Item item : order.items()) {
                items.add(new CreateDeliveryCommand.Item(item.orderProductId(), item.productId(),
                        item.name(), item.image(), Math.max(1, item.quantity()), item.skuCode()));
            }
        }
        return new CreateDeliveryCommand(
                orderId,
                customerId != null ? customerId : order.customerId(),
                number(event, "reorderedFromOrderId"),
                a.recipientName(),
                a.recipientPhone(),
                a.postalCode(),
                requireLine1(a.line1(), orderId),
                a.line2(),
                a.city(),
                a.state(),
                a.country(),
                null,
                items);
    }

    private static String requireLine1(String line, Long orderId) {
        if (line == null || line.isBlank()) {
            throw new IllegalStateException(
                    "주문 " + orderId + " 의 배송 주소가 비어 있습니다. "
                    + "주소 없이 배송을 만들면 송장을 낼 수 없습니다.");
        }
        return line;
    }

    private List<CreateDeliveryCommand.Item> readItems(JsonNode event) {
        List<CreateDeliveryCommand.Item> items = new ArrayList<>();
        JsonNode array = firstOf(event, "orderedProducts", "items", "products");
        if (array == null || !array.isArray()) {
            return items;
        }
        for (JsonNode node : array) {
            items.add(new CreateDeliveryCommand.Item(
                    number(node, "orderProductId", "id"),
                    number(node, "productId"),
                    text(node, "name", "productName"),
                    text(node, "image", "imageUrl"),
                    java.util.Optional.ofNullable(number(node, "quantity", "qty")).orElse(1L).intValue(),
                    text(node, "skuCode")));
        }
        return items;
    }

    // ── 관대한 읽기 헬퍼 ─────────────────────────────────────────────────────

    private static JsonNode firstOf(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private static String addressText(JsonNode address, String... names) {
        return address == null ? null : text(address, names);
    }

    private static String text(JsonNode node, String... names) {
        JsonNode value = firstOf(node, names);
        return value == null ? null : value.asText();
    }

    private static Long number(JsonNode node, String... names) {
        JsonNode value = firstOf(node, names);
        if (value == null) {
            return null;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        try {
            return Long.parseLong(value.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String abbreviate(String raw) {
        return raw == null ? "" : raw.substring(0, Math.min(raw.length(), 200));
    }
}
