package com.project.delivery_service.dto;

import java.util.List;

/** 결제 완료 이벤트에서 배송을 만들 때 필요한 값. */
public record CreateDeliveryCommand(
        Long orderId,
        Long customerId,
        /** 취소 후 재주문이면 원래 주문 번호. 아니면 null. */
        Long reorderedFromOrderId,
        String recipientName,
        String recipientPhone,
        String postalCode,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        /** ISO 3166-1 alpha-2. 국제 배송 판정의 근거다. */
        String country,
        String deliveryMemo,
        List<Item> items) {

    /** @param skuCode 사이즈 한 줄의 SKU(catalog V17) — 포장 목록에 적는다. 없으면 null. */
    public record Item(Long orderProductId, Long productId, String productName,
                       String imageUrl, int quantity, String skuCode) {}
}
