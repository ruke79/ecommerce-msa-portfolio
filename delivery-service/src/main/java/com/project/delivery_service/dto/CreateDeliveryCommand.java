package com.project.delivery_service.dto;

import java.util.List;

/** 결제 완료 이벤트에서 배송을 만들 때 필요한 값. */
// [JA] 決済完了イベントから配送を作るときに必要な値。
public record CreateDeliveryCommand(
        Long orderId,
        Long customerId,
        /** 취소 후 재주문이면 원래 주문 번호. 아니면 null. */
        // [JA] キャンセル後の再注文なら元の注文番号。そうでなければ null。
        Long reorderedFromOrderId,
        String recipientName,
        String recipientPhone,
        String postalCode,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        /** ISO 3166-1 alpha-2. 국제 배송 판정의 근거다. */
        // [JA] ISO 3166-1 alpha-2。国際配送判定の根拠である。
        String country,
        String deliveryMemo,
        List<Item> items) {

    /** @param skuCode 사이즈 한 줄의 SKU(catalog V17) — 포장 목록에 적는다. 없으면 null. */
    // [JA] skuCode: サイズ一行の SKU(catalog V17) — 梱包リストに記載する。なければ null。
    public record Item(Long orderProductId, Long productId, String productName,
                       String imageUrl, int quantity, String skuCode) {}
}
