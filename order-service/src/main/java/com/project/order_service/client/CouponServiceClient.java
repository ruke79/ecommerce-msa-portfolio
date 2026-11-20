package com.project.order_service.client;

import java.math.BigDecimal;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.project.order_service.config.FeignConfig;

/**
 * 쿠폰이 얼마를 깎는지 물어본다.
 *
 * <p>주문을 만들 때 총액을 <b>서버에서</b> 계산하기 위한 것이다(docs/40 #10). 예전에는
 * 브라우저가 보낸 {@code total} 을 그대로 저장했고, PayPal 검증은 그 값과 결제액을
 * 대조하기만 했다 — 즉 DevTools 로 총액을 1로 바꾸면 1달러 결제가 <b>정상적으로</b>
 * 검증을 통과했다.
 *
 * <p>🔴 <b>견적이지 사용 처리가 아니다.</b> 주문 생성이 쿠폰을 소모하면 결제에 실패하거나
 * 사용자가 결제 화면에서 이탈했을 때 쓰지도 않은 쿠폰이 사라진다. 쿠폰 사용 이벤트는
 * 원래대로 적용 시점({@code /applycoupon})에만 발행된다.
 *
 * <p>누구의 견적인가는 <b>사용자 id</b> 로 묻는다(docs/48 §6-⑥). 예전에는 이메일을 넘겼고 coupon 이 그 이메일로
 * user-service 에 id 를 다시 물었다. {@code User-Role} 등 신원 헤더는 게이트웨이만 붙이므로 이 호출에는 없고,
 * 저쪽은 그것을 "내부 호출" 로 읽는다.
 *
 * <p>[JA] クーポンがいくら割り引くかを尋ねる。
 * 注文を作るとき総額を <b>サーバーで</b> 計算するためのものだ(docs/40 #10)。以前はブラウザが送った {@code total} をそのまま保存し、
 * PayPal の検証はその値と決済額を照合するだけだった — つまり DevTools で総額を 1 に変えると 1 ドルの決済が <b>正常に</b> 検証を通った。
 * 🔴 <b>見積もりであって使用処理ではない。</b> 注文作成がクーポンを消費すると、決済に失敗したり決済画面で離脱したとき
 * 使ってもいないクーポンが消える。クーポン使用イベントは従来どおり適用時点({@code /applycoupon})にだけ発行される。
 * 誰の見積もりかは <b>ユーザー id</b> で尋ねる(docs/48 §6-⑥)。以前はメールを渡し、coupon がそのメールで user-service に id を問い直していた。
 * {@code User-Role} などの身元ヘッダーはゲートウェイだけが付けるのでこの呼び出しにはなく、相手はそれを「内部呼び出し」と読む。
 */
@FeignClient(name = "coupon-service", url = "${feign.coupon-url}", configuration = FeignConfig.class)
public interface CouponServiceClient {

    /**
     * @param cart 주문이 읽은 그 장바구니 — coupon 은 이것으로 계산한다. 🐛 예전에는 coupon 이 장바구니를 다시 읽었고, 그 사이에 줄을
     *             빼면 품목과 결제 금액이 서로 다른 장바구니에서 나왔다(CouponService.quote).
     *     [JA] 注文が読んだそのカート — coupon はこれで計算する。🐛 以前は coupon がカートを読み直し、その間に行を消すと品目と決済金額が別々のカートから出ていた(CouponService.quote)。
     */
    @PostMapping("api/coupon/internal/quote")
    Quote quote(@RequestParam("userId") Long userId, @RequestParam("couponName") String couponName,
            @org.springframework.web.bind.annotation.RequestBody com.project.common.dto.CartDto cart);

    /**
     * 결제를 시작한다 — 이 주문이 쿠폰을 {@code holdSeconds} 동안 잡는다(coupon V7). 결과는 {@code CLAIMED · NOT_FOUND · USED ·
     * IN_PAYMENT_ELSEWHERE} 중 하나. 판단은 {@link com.project.order_service.service.OrderCouponClaim}.
     *
     * <p>[JA] 決済を始める — この注文がクーポンを {@code holdSeconds} の間押さえる(coupon V7)。結果は {@code CLAIMED · NOT_FOUND · USED ·
     * IN_PAYMENT_ELSEWHERE} のいずれか。判断は {@link com.project.order_service.service.OrderCouponClaim}。
     */
    @PostMapping("api/coupon/internal/payment-claim")
    PaymentClaim claimForPayment(@RequestParam("userId") Long userId, @RequestParam("couponName") String couponName,
            @RequestParam("orderId") Long orderId, @RequestParam("holdSeconds") long holdSeconds);

    record PaymentClaim(String result) {
    }

    /**
     * coupon-service 의 {@code CouponResponse} 중 금액 계산에 필요한 부분.
     *
     * <p>저쪽 DTO 를 그대로 가져다 쓰지 않는 이유는 모듈 경계다 — coupon-service 의 내부
     * 표현이 바뀌어도 여기가 깨지지 않는다. 모르는 필드는 무시된다.
     *
     * <p>[JA] coupon-service の {@code CouponResponse} のうち金額計算に必要な部分。
     * 相手の DTO をそのまま持ってきて使わない理由はモジュール境界だ — coupon-service の内部表現が変わってもここが壊れない。知らないフィールドは無視される。
     *
     * @param totalAfterDiscount 할인 후 총액
     *     [JA] 割引後の総額
     * @param discountAmount     실제로 깎인 금액
     *     [JA] 実際に割り引かれた金額
     * @param message            비어 있지 않으면 <b>적용되지 않았다</b>는 뜻이고 그 사유다
     *     [JA] 空でなければ <b>適用されなかった</b> という意味で、その理由だ
     */
    record Quote(BigDecimal totalAfterDiscount, BigDecimal discountAmount, String message) {

        /** 실제로 할인이 적용됐는가. 사유가 붙어 있으면 적용되지 않은 것이다. */
        // [JA] 実際に割引が適用されたか。理由が付いていれば適用されていない。
        public boolean applied() {
            return (message == null || message.isBlank()) && discountAmount != null;
        }
    }
}
