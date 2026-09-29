package com.project.delivery_service.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.project.common.i18n.ClientText;
import com.project.delivery_service.domain.Carrier;
import com.project.delivery_service.domain.Delivery;
import com.project.delivery_service.domain.DeliveryEvent;
import com.project.delivery_service.domain.Shipment;

/**
 * 배송 조회 응답 — 아마존식 화면이 필요로 하는 형태.
 *
 * <p>내부 상태 그대로가 아니라 {@code stage} 를 함께 내려준다. 프론트가 상태 enum 을
 * 화면 단계로 매핑하는 로직을 따로 갖게 되면, 상태를 하나 추가할 때마다 양쪽을 고쳐야 하고
 * 둘이 어긋나면 화면이 조용히 틀린다.
 */
public record DeliveryResponse(
        Long deliveryId,
        /**
         * 🐛 <b>문자열이다.</b> 예전에는 {@code Long} 이었고, 그래서 관리자 화면의 송장 등록이
         * 언제나 "주문 또는 택배사를 찾을 수 없습니다" 였다.
         *
         * <p>주문 번호는 TSID 라 18자리다({@code 889196209423729897}). 자바에서는 {@code long}
         * 이라 멀쩡하지만, JSON 숫자로 내려보내면 브라우저의 {@code JSON.parse} 가
         * <b>배정밀도 실수</b>로 읽는다. 안전 정수 한계는 {@code 2^53-1}(약 9.0e15)인데 이
         * 값은 8.9e17 이라, 화면에 도착한 순간 {@code 889196209423729920} 으로 바뀐다.
         * 그 번호로 송장을 등록하면 서버는 그런 주문을 모른다 — 화면에는 번호가 맞게
         * 보이므로 무엇이 틀렸는지 알 수 없다.
         *
         * <p>저장소의 다른 DTO 들이 id 를 {@code String} 으로 내리는 이유가 이것이다
         * ({@code OrderDto.id}, {@code OrderedProductDto.id}).
         */
        String orderId,
        String status,
        String stage,
        LocalDateTime estimatedAt,
        boolean canChangeAddress,
        boolean canCancel,
        /** 주문 번호와 같은 이유로 문자열이다. */
        String reorderedFromOrderId,
        AddressView address,
        List<ShipmentView> shipments) {

    public record AddressView(String recipientName, String recipientPhone,
                              String postalCode, String line1, String line2,
                              String city, String state, String country, String memo) {}

    public record ShipmentView(
            Long shipmentId,
            int sequenceNo,
            String carrierCode,
            String carrierName,
            String trackingNumber,
            String trackingUrl,
            String status,
            String stage,
            boolean international,
            LocalDateTime estimatedAt,
            LocalDateTime deliveredAt,
            List<ItemView> items,
            List<EventView> events) {}

    /** @param skuCode 사이즈 한 줄의 SKU — 포장 목록 · 관리자 화면. 없으면 null. */
    public record ItemView(Long productId, String productName, String imageUrl, int quantity, String skuCode) {}

    public record EventView(String status, String stage, LocalDateTime occurredAt,
                            String location, String description) {}

    public static DeliveryResponse from(Delivery delivery,
                                        java.util.Map<String, Carrier> carriers) {
        return new DeliveryResponse(
                delivery.getDeliveryId(),
                String.valueOf(delivery.getOrderId()),
                delivery.overallStatus().name(),
                delivery.overallStatus().stage().name(),
                delivery.overallEstimatedAt(),
                delivery.canChangeAddress(),
                delivery.canChangeAddress(),   // 취소 가능 조건도 '발송 전'으로 같다
                delivery.getReorderedFromOrderId() == null ? null
                        : String.valueOf(delivery.getReorderedFromOrderId()),
                new AddressView(delivery.getRecipientName(), delivery.getRecipientPhone(),
                        delivery.getPostalCode(), delivery.getAddressLine1(),
                        delivery.getAddressLine2(), delivery.getCity(), delivery.getState(),
                        delivery.getCountry(), delivery.getDeliveryMemo()),
                delivery.getShipments().stream()
                        .map(s -> toShipmentView(s, carriers.get(s.getCarrierCode())))
                        .toList());
    }

    private static ShipmentView toShipmentView(Shipment s, Carrier carrier) {
        return new ShipmentView(
                s.getShipmentId(),
                s.getSequenceNo(),
                s.getCarrierCode(),
                carrier == null ? null : ClientText.localize(carrier.getNameKo()),
                s.getTrackingNumber(),
                carrier == null ? null : carrier.trackingUrlFor(s.getTrackingNumber()),
                s.getStatus().name(),
                s.getStatus().stage().name(),
                s.isInternational(),
                s.getEstimatedAt(),
                s.getDeliveredAt(),
                s.getItems().stream()
                        .map(i -> new ItemView(i.getProductId(), i.getProductName(),
                                i.getImageUrl(), i.getQuantity(), i.getSkuCode()))
                        .toList(),
                s.getEvents().stream().map(DeliveryResponse::toEventView).toList());
    }

    /**
     * 장소·설명은 저장된 한국어를 화면 언어로 바꿔 내준다(docs/60 §10). 테스트배송의 문구와 택배사 이름만 카탈로그에 있다 —
     * 실제 택배사가 보낸 문구는 데이터라 원문 그대로 나간다. 요청 밖(리스너·스케줄러)에서 부르면 그대로다.
     */
    private static EventView toEventView(DeliveryEvent e) {
        return new EventView(e.getStatus().name(), e.getStatus().stage().name(),
                e.getOccurredAt(), ClientText.localize(e.getLocation()),
                ClientText.localize(e.getDescription()));
    }
}
