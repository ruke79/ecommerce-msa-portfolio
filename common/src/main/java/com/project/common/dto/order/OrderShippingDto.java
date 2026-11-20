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
 *
 * <p>[JA] 配送を作るのに必要な注文の断面 — <b>誰の、どの注文が、どこへ、何を</b>。
 * order-service の {@code GET /api/order/internal/orders/{orderId}/shipping} の応答で、delivery-service が決済完了イベントを受け取った後にこれを読んで配送を作る。
 * なぜ決済イベントに載せないか: {@code payment} トピックの {@code PaymentResponse} は決済ドメインの事実(注文 · 金額 · 状態)だけを載せる。配送先と品目を
 * そこに挟み込むと、pay-service が自分と無関係な注文データを持ち運ぶことになり、Avro スキーマも三つのサービスが一緒に変える必要がある。配送は注文の持ち主である
 * order-service に直接問い合わせる — 等級バッチが購入集計を問い合わせるのと同じ形である。
 * 金額はない。配送には不要で、あるとログと応答に決済情報が広がる。
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
     *
     * <p>[JA] 受取人 · 配送先のスナップショット。注文時点の {@code OrderAddress} を配送サービスのフィールド名に移したものである — 受け取る側が firstname/lastname · zipCode のような注文側の名前を知る必要はない。
     */
    public record Address(
            String recipientName,
            String recipientPhone,
            String postalCode,
            String line1,
            String line2,
            String city,
            String state,
            /** ISO 3166-1 alpha-2. 없으면 받는 쪽이 발송국으로 본다. ／ [JA] ISO 3166-1 alpha-2。なければ受け取る側が発送国とみなす。 */
            String country) {
    }

    /**
     * @param skuCode 사이즈 한 줄의 SKU(catalog V17) — 포장 목록에 적는다. 이전 주문은 null. 이 값을 모르는 옛 order-service
     *                가 보낸 응답에서도 null 이다(받는 쪽은 없어도 동작한다).
     *
     * <p>[JA] skuCode: サイズ一行の SKU(catalog V17) — 梱包リストに記す。以前の注文は null。この値を知らない古い order-service が送った応答でも null である(受け取る側はなくても動作する)。
     */
    public record Item(Long orderProductId, Long productId, String name, String image, int quantity, String skuCode) {

        /** SKU 가 없던 때의 모양 — 기존 호출(테스트 등)을 그대로 둔다. ／ [JA] SKU がなかった頃の形 — 既存の呼び出し(テストなど)をそのまま残す。 */
        public Item(Long orderProductId, Long productId, String name, String image, int quantity) {
            this(orderProductId, productId, name, image, quantity, null);
        }
    }
}
