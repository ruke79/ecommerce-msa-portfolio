package com.project.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;


import lombok.AccessLevel;

@Builder
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class CartProductDto {

    /** 장바구니 줄의 id({@code cart_product_id}) — 상품 id 가 아니다. ／ [JA] カート行の id({@code cart_product_id}) — 商品 id ではない。 */
    private String id;

    /**
     * 이 줄이 가리키는 <b>상품</b>의 id.
     *
     * <p>예전에는 내려보내지 않았다. 그래서 장바구니를 받은 쪽은 "이 줄이 어떤 상품인가"를
     * 알 수 없었고, 알아내려면 줄마다 {@code GET /api/cart/{cartProductId}} 를 한 번씩
     * 불러야 했다. 쿠폰의 상품·카테고리 제약을 판정하려면 이 값이 필요하다
     * (coupon-service 가 이 id 들로 카테고리를 <b>한 번에</b> 조회한다).
     *
     * <p>엔티티에 이미 있는 값이라 추가 조회는 없다.
     *
     * <p>[JA] この行が指す<b>商品</b>の id。
     * 以前は返していなかった。そのためカートを受け取った側は「この行がどの商品か」を知ることができず、知るには行ごとに {@code GET /api/cart/{cartProductId}} を
     * 一回ずつ呼ぶ必要があった。クーポンの商品 · カテゴリ制約を判定するにはこの値が必要である(coupon-service がこの id でカテゴリを<b>一度に</b>照会する)。
     * エンティティにすでにある値なので追加の照会はない。
     */
    private String productId;

    private String name;
    private String image;
    private String size;

    /**
     * 이 줄의 SKU — 사이즈 한 줄의 재고 단위 코드(catalog V17, 예: {@code FAS-100042-XL}). 주문 품목이 그대로 기록한다.
     * 코드가 생기기 전의 상품이면 null 이다.
     *
     * <p>[JA] この行の SKU — サイズ一行の在庫単位コード(catalog V17、例: {@code FAS-100042-XL})。注文品目がそのまま記録する。コードができる前の商品なら null である。
     */
    private String skuCode;

    private int qty;
    private ProductColorDto color;

    /** 단가. 이 줄의 상품 금액은 {@code price × qty} 다 — 배송비는 포함되지 않는다. ／ [JA] 単価。この行の商品金額は {@code price × qty} である — 送料は含まれない。 */
    private BigDecimal price;

    /**
     * 이 줄의 배송비.
     *
     * <p>🔴 예전에는 <b>내려보내지 않았다.</b> cart-service 가 {@code price×qty + shipping}
     * 으로 총액에 합산해 버리고 항목에는 남기지 않아서, 장바구니를 받은 쪽은 "이 총액에
     * 배송비가 얼마 들어 있는가"를 알 수 없었다. 그 결과:
     *
     * <ul>
     *   <li>결제 화면이 배송비를 따로 보여 주지 못했다 — 사용자는 자기가 배송비를 얼마
     *       내는지 <b>주문하는 순간까지 모른다</b></li>
     *   <li>{@code Order.shippingPrice} 는 컬럼만 있고 <b>한 번도 채워진 적이 없다</b>(항상 0)</li>
     *   <li>제약 없는 쿠폰이 총액을 기준으로 할인해서 <b>배송비까지 깎였다</b> — 제약 있는
     *       쿠폰은 줄 금액만 봐서 안 깎였다. 같은 장바구니에 쿠폰 종류만 달라도 할인액이
     *       달랐다</li>
     * </ul>
     *
     * <p>수량과 무관한 <b>줄 단위</b> 금액이다({@code computeCartTotal} 이 그렇게 더한다).
     *
     * <p>[JA] この行の送料。
     * 🔴 以前は<b>返していなかった。</b>cart-service が {@code price×qty + shipping} で総額に合算してしまい項目には残さなかったので、カートを受け取った側は
     * 「この総額に送料がいくら入っているか」を知ることができなかった。その結果: 決済画面が送料を別に表示できなかった — 利用者は自分が送料をいくら払うか
     * <b>注文する瞬間まで分からない</b>。{@code Order.shippingPrice} はカラムだけあって<b>一度も埋まったことがない</b>(常に 0)。制約のないクーポンが総額基準で
     * 割引して<b>送料まで値引きされた</b> — 制約のあるクーポンは行の金額だけを見て値引きされなかった。同じカートでもクーポンの種類だけで割引額が違った。
     * 数量に関係ない<b>行単位</b>の金額である({@code computeCartTotal} がそう足す)。
     */
    private BigDecimal shipping;

    private int style;

}
