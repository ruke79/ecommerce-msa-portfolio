package com.project.common.dto.order.request;


import java.util.List;

import com.project.common.dto.CartProductDto;
import com.project.common.dto.order.OrderAddressDto;

import lombok.Getter;
import lombok.Setter;

/**
 * 주문 생성 요청.
 *
 * <h3>🔴 여기에 금액 필드가 없는 것은 실수가 아니다</h3>
 *
 * <p>예전에는 {@code total} 과 {@code totalBeforeDiscount} 가 있었고, 서버는 그 값을
 * <b>그대로 저장했다.</b> 그 값은 브라우저가 계산한 것이다
 * ({@code Summary.jsx}: {@code total: totalAfterDiscount || cart.cartTotal}).
 *
 * <p>PayPal 검증이 있으니 괜찮다고 볼 수 없었다. 그 검증은 결제액과 <b>DB 의 주문 총액</b>이
 * 같은지만 본다. 총액 자체가 브라우저에서 왔으므로 DevTools 로 {@code total: 1} 을 보내고
 * 1달러를 결제하면 검증은 정상 통과했다(docs/40 #10).
 *
 * <p>이제 금액은 서버가 정한다 — 장바구니 총액은 cart-service 가 카탈로그 가격으로
 * 계산한 값을 쓰고, 할인액은 coupon-service 에 다시 물어본다
 * ({@code OrderDataService.priceOrder}). 필드를 남겨 두고 "읽지 않는다" 고 주석만
 * 달았다면, 다음 사람이 "여기 total 이 있네" 하고 다시 쓰게 된다. 없애는 것이 유일하게
 * 확실한 방법이다.
 *
 * <p>같은 이유로 {@code products} 도 <b>읽지 않는다.</b> 무엇을 주문했는가는 서버가 읽은
 * 장바구니가 정한다. 다만 이 필드는 금액이 아니고 다른 클라이언트가 보내고 있을 수 있어
 * 받기만 하고 버린다 — Jackson 이 모르는 필드를 무시하므로 지워도 요청은 깨지지 않지만,
 * 금액만큼 급하지 않다.
 *
 * <p>[JA] 注文作成リクエスト。
 * 🔴 ここに金額フィールドがないのはミスではない: 以前は {@code total} と {@code totalBeforeDiscount} があり、サーバーはその値を<b>そのまま保存していた。</b>
 * その値はブラウザが計算したものである({@code Summary.jsx}: {@code total: totalAfterDiscount || cart.cartTotal})。
 * PayPal の検証があるから大丈夫とは言えなかった。その検証は決済額と<b>DB の注文総額</b>が同じかだけを見る。総額自体がブラウザから来たので、DevTools で
 * {@code total: 1} を送り 1 ドルを決済すれば検証は正常に通った(docs/40 #10)。
 * 今は金額をサーバーが決める — カート総額は cart-service がカタログ価格で計算した値を使い、割引額は coupon-service に改めて問い合わせる
 * ({@code OrderDataService.priceOrder})。フィールドを残して「読まない」とコメントだけ付けたら、次の人が「ここに total がある」と言ってまた使う。消すのが唯一確実な方法である。
 * 同じ理由で {@code products} も<b>読まない。</b>何を注文したかはサーバーが読んだカートが決める。ただしこのフィールドは金額ではなく、他のクライアントが送っている
 * 可能性があるので受け取るだけで捨てる — Jackson は未知のフィールドを無視するので消してもリクエストは壊れないが、金額ほど急ぎではない。
 */
@Setter
@Getter
public class OrderRequest {

    /** ⚠️ 읽지 않는다. 주문 품목은 서버가 읽은 장바구니가 정한다. ／ [JA] ⚠️ 読まない。注文品目はサーバーが読んだカートが決める。 */
    private List<CartProductDto> products;

    /** 없으면 서비스가 "배송지를 선택해 주세요." 로 거절한다(@NotNull 이 아니다 — 그 문구를 지킨다). 있으면 안을 검증한다. */
    // [JA] なければサービスが「配送先を選択してください」で拒否する。あれば中身を検証する。
    @jakarta.validation.Valid
    private OrderAddressDto shippingAddress;
    private String paymentMethod;

    /** 적용할 쿠폰 이름. 할인 <b>금액</b>은 여기서 받지 않고 coupon-service 에 물어본다. ／ [JA] 適用するクーポン名。割引<b>金額</b>はここで受け取らず coupon-service に問い合わせる。 */
    private String couponApplied;

    // 신원 필드(email)는 없다 — 주문자는 게이트웨이가 검증한 User-Id 다. 옛 클라이언트가 보내도 Jackson 이 버린다.
    // [JA] 身元フィールド(email)はない — 注文者はゲートウェイが検証した User-Id である。古いクライアントが送っても Jackson が捨てる。
}
