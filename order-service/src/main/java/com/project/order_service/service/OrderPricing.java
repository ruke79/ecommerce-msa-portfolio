package com.project.order_service.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Service;

import com.project.common.dto.CartDto;
import com.project.order_service.client.CouponServiceClient;
import com.project.order_service.domain.exception.OrderDomainException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 주문 금액을 정하는 곳.
 *
 * <p><b>왜 별도 클래스인가.</b> 금액 산정은 이 시스템에서 가장 조심해야 할 계산인데,
 * {@code OrderDataService} 안에 있으면 그 사실이 조회·페이징·PayPal 검증 사이에 묻힌다.
 * 무엇보다 <b>이것만 따로 테스트할 수 있어야 한다</b> — 주문 서비스 전체를 세우지 않고.
 *
 * <p>[JA] 注文金額を決める場所。
 *
 * <p><b>なぜ別クラスなのか。</b>金額算定はこのシステムで最も慎重であるべき計算だが、
 * {@code OrderDataService} の中にあると、その事実が照会・ページング・PayPal 検証の間に埋もれてしまう。
 * 何より<b>これだけを単独でテストできなければならない</b> — 注文サービス全体を立ち上げずに。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPricing {

    private final CouponServiceClient couponServiceClient;

    /**
     * 주문 금액을 <b>서버가</b> 정한다 (docs/40 #10).
     *
     * <h3>🔴 무엇이 문제였나</h3>
     *
     * <p>예전에는 요청 본문의 {@code total} 과 {@code totalBeforeDiscount} 를 그대로
     * 저장했다. 그 값은 브라우저가 계산한 것이다
     * ({@code Summary.jsx}: {@code total: totalAfterDiscount || cart.cartTotal}).
     *
     * <p>PayPal 검증이 있으니 괜찮다고 볼 수 없다. 그 검증은 <b>결제액과 DB 의 주문
     * 총액이 같은지</b>만 본다({@code persisitPaypalPayment}). 총액 자체가 브라우저에서
     * 왔으므로, DevTools 로 {@code total: 1} 을 보내고 1달러를 결제하면 <b>검증은 정상
     * 통과한다.</b> 자물쇠는 튼튼한데 열쇠를 손님이 깎고 있었다.
     *
     * <h3>이제 무엇을 믿는가</h3>
     *
     * <ul>
     *   <li><b>장바구니 총액</b> — cart-service 가 카탈로그 가격으로 직접 계산해 저장한
     *       값이다({@code computeCartTotal}). 이미 위에서 받아 온 {@code latestCart} 다.</li>
     *   <li><b>할인액</b> — coupon-service 에 <b>다시 물어본다.</b> 장바구니에 저장된
     *       {@code totalAfterDiscount} 를 쓰지 않는 이유는 그것이 Kafka 로 비동기 갱신되기
     *       때문이다 — 쿠폰을 적용하고 바로 주문하면 아직 도착하지 않았을 수 있다.</li>
     * </ul>
     *
     * <p>본문의 금액은 <b>읽지 않는다.</b> 힌트로도 쓰지 않는다 — 값을 비교해 경고만 남기는
     * 설계도 생각했지만, 쓰이지 않는 필드가 남아 있으면 다음 사람이 "여기 total 이 있네" 하고
     * 다시 쓰게 된다.
     *
     * <h3>쿠폰을 확인하지 못하면 주문을 거절한다</h3>
     *
     * <p>coupon-service 가 답하지 못할 때 <b>할인 없이</b> 진행하면, 사용자는 화면에서 본
     * 금액과 다른 금액을 결제하게 된다. 조용히 더 받는 것보다 "지금은 안 된다"가 낫다.
     *
     * <h3>금액은 소수 둘째 자리까지다</h3>
     *
     * <p>🐛 예전에는 {@code Order.total} 이 {@code int} 라 소수점이 잘렸다. $59.98 이 59 로
     * 저장되고, 프론트가 PayPal 에 보내는 금액도 같은 59 라 검증은 통과하지만 <b>실제로는
     * 98센트를 덜 받았다.</b> V7 에서 네 금액 컬럼을 {@code numeric(19,2)} 로 옮겼고, 여기서는
     * 상점 통화의 자릿수(USD 2)로 <b>반올림</b>한다({@link RoundingMode#HALF_UP}).
     *
     * <p>버림이 아니라 반올림인 이유: 카탈로그 가격은 이미 둘째 자리까지라 반올림이 실제로
     * 일어나는 경우는 <b>쿠폰 할인율</b>뿐이고($59.98 × 10% = 5.998), 그때 반 센트는
     * 어느 쪽으로 가도 "표시된 금액과 다르다" 는 문제가 없다 — 화면도 같은 값을 서버에서
     * 받아 보여 주기 때문이다({@code OrderDto.total}).
     *
     * <p>[JA] 注文金額は<b>サーバーが</b>決める (docs/40 #10)。
     *
     * <h3>🔴 何が問題だったか</h3>
     *
     * <p>以前はリクエスト本文の {@code total} と {@code totalBeforeDiscount} をそのまま
     * 保存していた。その値はブラウザが計算したものである
     * ({@code Summary.jsx}: {@code total: totalAfterDiscount || cart.cartTotal})。
     *
     * <p>PayPal の検証があるから大丈夫、とは言えない。その検証は<b>決済額と DB の注文
     * 総額が一致するか</b>しか見ない({@code persisitPaypalPayment})。総額そのものがブラウザから
     * 来ているので、DevTools で {@code total: 1} を送って 1 ドルを決済すれば<b>検証は正常に
     * 通ってしまう。</b>錠前は頑丈なのに、その鍵を客が自分で削っていたのである。
     *
     * <h3>今は何を信じるのか</h3>
     *
     * <ul>
     *   <li><b>カート総額</b> — cart-service がカタログ価格で自ら計算して保存した
     *       値である({@code computeCartTotal})。上ですでに受け取っている {@code latestCart} だ。</li>
     *   <li><b>割引額</b> — coupon-service に<b>改めて問い合わせる。</b>カートに保存された
     *       {@code totalAfterDiscount} を使わない理由は、それが Kafka で非同期に更新される
     *       からである — クーポンを適用してすぐ注文すると、まだ届いていない可能性がある。</li>
     * </ul>
     *
     * <p>本文の金額は<b>読まない。</b>ヒントとしても使わない — 値を比較して警告だけ残す
     * 設計も考えたが、使われないフィールドが残っていると、次の人が「ここに total があるな」と
     * また使い始めてしまう。
     *
     * <h3>クーポンを確認できなければ注文を拒否する</h3>
     *
     * <p>coupon-service が応答できない時に<b>割引なしで</b>進めると、ユーザーは画面で見た
     * 金額と異なる金額を決済することになる。黙って多く取るより「今はできない」の方がましである。
     *
     * <h3>金額は小数第 2 位までである</h3>
     *
     * <p>🐛 以前は {@code Order.total} が {@code int} だったため小数点以下が切り捨てられていた。
     * $59.98 が 59 で保存され、フロントが PayPal に送る金額も同じ 59 なので検証は通るが、<b>実際には
     * 98 セント少なく受け取っていた。</b>V7 で 4 つの金額カラムを {@code numeric(19,2)} に移し、ここでは
     * 店舗通貨の桁数(USD 2)で<b>四捨五入</b>する({@link RoundingMode#HALF_UP})。
     *
     * <p>切り捨てではなく四捨五入である理由: カタログ価格はすでに第 2 位までなので、丸めが実際に
     * 起きるのは<b>クーポンの割引率</b>の場合だけであり($59.98 × 10% = 5.998)、その時の半セントは
     * どちらに寄っても「表示された金額と違う」という問題は生じない — 画面も同じ値をサーバーから
     * 受け取って表示するからである({@code OrderDto.total})。
     */
    /**
     * @param userId 견적의 주인 — 검증된 토큰의 uid
     *               [JA] 見積もりの持ち主 — 検証済みトークンの uid
     */
    public Amounts priceOrder(CartDto cart, String couponName, Long userId) {
        BigDecimal beforeDiscount = cart.getCartTotal() == null ? BigDecimal.ZERO : cart.getCartTotal();
        BigDecimal shipping = shippingOf(cart);

        if (couponName == null || couponName.isBlank()) {
            return new Amounts(toAmount(beforeDiscount), toAmount(beforeDiscount),
                    toAmount(shipping), null);
        }

        CouponServiceClient.Quote quote;
        try {
            // 품목을 만든 그 장바구니로 계산하게 한다 — coupon 이 다시 읽으면 그 사이 바뀐 장바구니의 금액이 청구됐다(CP1).
            quote = couponServiceClient.quote(userId, couponName, cart);
        } catch (RuntimeException e) {
            log.warn("주문 생성: 쿠폰 견적을 받지 못했다 — 주문을 거절한다. {}", e.toString());
            throw new OrderDomainException(
                    "쿠폰을 확인하지 못했습니다. 잠시 후 다시 시도하거나 쿠폰을 빼고 주문해 주세요.");
        }

        if (quote == null || !quote.applied()) {
            String reason = quote == null ? null : quote.message();
            log.info("주문 생성: 쿠폰이 적용되지 않았다 — 주문을 거절한다. reason={}", reason);
            throw new OrderDomainException(
                    reason == null || reason.isBlank()
                            ? "쿠폰을 적용할 수 없습니다. 쿠폰을 빼고 다시 시도해 주세요."
                            : reason);
        }

        BigDecimal afterDiscount = quote.totalAfterDiscount() == null
                ? beforeDiscount.subtract(quote.discountAmount())
                : quote.totalAfterDiscount();

        // 음수 총액은 어떤 경우에도 만들지 않는다. 할인액이 총액을 넘는 설정 실수가
        // 결제 금액 음수로 이어지면 그때는 이미 돈 문제다.
        // [JA] 負の総額はいかなる場合も作らない。割引額が総額を超えるような設定ミスが
        //      マイナスの決済金額につながったら、その時点ですでにお金の問題である。
        if (afterDiscount.signum() < 0) {
            afterDiscount = BigDecimal.ZERO;
        }

        return new Amounts(toAmount(afterDiscount), toAmount(beforeDiscount),
                toAmount(shipping), couponName);
    }

    /**
     * 이 주문의 배송비.
     *
     * <p>🔴 <b>예전에는 알 수가 없었다.</b> cart-service 가 {@code price×qty + shipping} 으로
     * 총액에 합산해 버리고 항목에는 남기지 않아서, 주문이 아는 것은 "배송비가 포함된
     * 총액" 하나뿐이었다. {@code Order.shippingPrice} 컬럼은 <b>한 번도 채워진 적이
     * 없다</b>(항상 0) — 채울 값을 구할 방법이 없었기 때문이다.
     *
     * <p>이제 {@code CartProductDto.shipping} 이 줄마다 실려 온다. 수량과 무관한 줄 단위
     * 금액이라 그대로 더한다({@code computeCartTotal} 이 더하는 방식과 같아야 한다 —
     * 다르면 상품 금액 + 배송비 ≠ 총액이 되어 화면의 세 줄이 맞지 않는다).
     *
     * <p>옛 장바구니 캐시에는 이 필드가 없어 {@code null} 로 온다. 그때는 0 으로 본다 —
     * 배송비를 <b>없는 것으로</b> 보는 쪽이 안전하다. 반대로 추측하면 상품 금액이 실제보다
     * 작게 표시된다.
     *
     * <p>[JA] この注文の送料。
     *
     * <p>🔴 <b>以前は知る手段がなかった。</b>cart-service が {@code price×qty + shipping} として
     * 総額に合算してしまい、項目には残さなかったため、注文が知っているのは「送料込みの
     * 総額」ひとつだけだった。{@code Order.shippingPrice} カラムは<b>一度も埋まったことが
     * ない</b>(常に 0) — 埋める値を得る方法がなかったからである。
     *
     * <p>今は {@code CartProductDto.shipping} が行ごとに載ってくる。数量に依存しない行単位の
     * 金額なのでそのまま足す({@code computeCartTotal} が足す方法と同じでなければならない —
     * 異なると商品金額 + 送料 ≠ 総額となり、画面の 3 行が合わなくなる)。
     *
     * <p>古いカートキャッシュにはこのフィールドがなく {@code null} で来る。その時は 0 とみなす —
     * 送料を<b>無いものとして</b>扱う方が安全である。逆に推測すると、商品金額が実際より
     * 小さく表示されてしまう。
     */
    private static BigDecimal shippingOf(CartDto cart) {
        if (cart.getProducts() == null) {
            return BigDecimal.ZERO;
        }
        return cart.getProducts().stream()
                .map(p -> p.getShipping() == null ? BigDecimal.ZERO : p.getShipping())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 상점 통화의 자릿수(컬럼 {@code numeric(19,2)})로. {@code null} 은 0. */
    // [JA] 店舗通貨の桁数(カラム {@code numeric(19,2)})に丸める。{@code null} は 0。
    private static BigDecimal toAmount(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** 주문 금액 컬럼의 소수 자릿수(V7 {@code numeric(19,2)}). */
    // [JA] 注文金額カラムの小数桁数(V7 {@code numeric(19,2)})。
    static final int SCALE = 2;

    /**
     * 서버가 정한 주문 금액 — 상점 통화, 소수 둘째 자리까지.
     *
     * <p>세 값의 관계: {@code total = totalBeforeDiscount - 할인액} 이고,
     * {@code shippingPrice} 는 그 안에 <b>포함된</b> 배송비 몫이다(빼는 값이 아니다).
     * 쿠폰은 배송비를 깎지 않으므로 배송비는 할인 전후로 같다.
     *
     * <p>[JA] サーバーが決めた注文金額 — 店舗通貨、小数第 2 位まで。
     *
     * <p>3 つの値の関係: {@code total = totalBeforeDiscount - 割引額} であり、
     * {@code shippingPrice} はその中に<b>含まれる</b>送料分である(差し引く値ではない)。
     * クーポンは送料を割り引かないので、送料は割引の前後で同じである。
     *
     * @param total              실제 청구 금액(할인 후, 배송비 포함)
     *                            [JA] 実際の請求金額(割引後、送料込み)
     * @param totalBeforeDiscount 할인 전 장바구니 총액(배송비 포함)
     *                            [JA] 割引前のカート総額(送料込み)
     * @param shippingPrice      그중 배송비 몫
     *                            [JA] そのうちの送料分
     * @param couponApplied      적용된 쿠폰 이름. 쿠폰이 없으면 {@code null}
     *                            [JA] 適用されたクーポン名。クーポンがなければ {@code null}
     */
    public record Amounts(BigDecimal total, BigDecimal totalBeforeDiscount, BigDecimal shippingPrice,
            String couponApplied) {

        /** 배송비를 뺀 상품 금액. 화면이 "상품 금액 / 배송비" 로 나눠 보여 줄 때 쓴다. */
        // [JA] 送料を除いた商品金額。画面が「商品金額 / 送料」に分けて表示する時に使う。
        public BigDecimal merchandise() {
            return totalBeforeDiscount.subtract(shippingPrice).max(BigDecimal.ZERO);
        }
    }
}
