package com.project.common.dto.order;

import java.util.List;

/**
 * 배송을 만드는 데 필요한 주문의 단면 — <b>누구의, 어느 주문이, 어디로, 무엇을</b>.
 *
 * <p>order-service 의 {@code GET /api/order/internal/orders/{orderId}/shipping} 응답이고,
 * delivery-service 가 결제 완료 이벤트를 받은 뒤 이것을 읽어 배송을 만든다.
 *
 * <h3>왜 결제 이벤트에 싣지 않나</h3>
 *
 * <p>{@code payment} 토픽의 {@code PaymentResponse} 는 결제 도메인의 사실(주문·금액·상태)만
 * 싣는다. 배송 주소와 품목을 거기 끼워 넣으면 pay-service 가 자기와 무관한 주문 데이터를
 * 들고 다녀야 하고, Avro 스키마도 세 서비스가 함께 바꿔야 한다. 배송은 주문의 주인인
 * order-service 에 직접 묻는다 — 등급 배치가 구매 집계를 묻는 것과 같은 모양이다.
 *
 * <p>금액은 없다. 배송에는 필요 없고, 있으면 로그와 응답에 결제 정보가 퍼진다.
 */
public record OrderShippingDto(
        Long orderId,
        Long customerId,
        String orderStatus,
        Address address,
        List<Item> items) {

    /**
     * 수취인·배송지 스냅샷. 주문 시점의 {@code OrderAddress} 를 배송 서비스의 필드 이름으로
     * 옮긴 것이다 — 받는 쪽이 firstname/lastname·zipCode 같은 주문 쪽 이름을 알 필요가 없다.
     */
    public record Address(
            String recipientName,
            String recipientPhone,
            String postalCode,
            String line1,
            String line2,
            String city,
            String state,
            /** ISO 3166-1 alpha-2. 없으면 받는 쪽이 발송국으로 본다. */
            String country) {
    }

    /**
     * @param skuCode 사이즈 한 줄의 SKU(catalog V17) — 포장 목록에 적는다. 이전 주문은 null. 이 값을 모르는 옛 order-service
     *                가 보낸 응답에서도 null 이다(받는 쪽은 없어도 동작한다).
     */
    public record Item(Long orderProductId, Long productId, String name, String image, int quantity, String skuCode) {

        /** SKU 가 없던 때의 모양 — 기존 호출(테스트 등)을 그대로 둔다. */
        public Item(Long orderProductId, Long productId, String name, String image, int quantity) {
            this(orderProductId, productId, name, image, quantity, null);
        }
    }
}
