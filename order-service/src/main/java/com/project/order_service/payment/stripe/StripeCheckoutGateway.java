package com.project.order_service.payment.stripe;

import java.math.BigDecimal;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.project.common.constants.PaymentProvider;
import com.project.order_service.model.Order;
import com.project.order_service.payment.CheckoutSession;
import com.project.order_service.payment.HostedCheckoutGateway;
import com.project.order_service.payment.PaymentCurrencyPolicy;
import com.project.order_service.payment.PaymentVerificationException;
import com.project.order_service.payment.RefundResult;
import com.project.order_service.payment.RefundingGateway;
import com.project.order_service.payment.VerifiedPayment;

import lombok.extern.slf4j.Slf4j;

/**
 * Stripe Checkout — <b>Stripe 가 호스팅하는 결제 페이지</b>.
 *
 * <h3>왜 Checkout(리디렉션)인가</h3>
 *
 * <p>카드 번호가 <b>우리 페이지를 전혀 거치지 않는다.</b> 브라우저는 Stripe 가 만든 URL 로
 * 이동했다 돌아올 뿐이라 PCI 범위가 최소이고, 프론트에 Stripe 키를 넣을 필요조차 없다 —
 * PayPal 에서 겪은 "빌드 시점에 박히는 client id" 문제가 구조적으로 생기지 않는다.
 *
 * <h3>🔒 금액은 서버가 정한다</h3>
 *
 * <p>세션을 만들 때 쓰는 금액은 <b>인자로 받은 주문의 총액</b>이다. 브라우저가 보낸 값으로
 * 세션을 만들면 결제 금액 자체를 손님이 정하게 되고, 그건 뒤에서 대조해도 늦다 — 사용자는
 * 자기가 승인한 금액을 냈다고 믿기 때문이다(docs/35).
 *
 * <h3>SDK 대신 RestClient 인 이유</h3>
 *
 * <p>쓰는 엔드포인트가 둘뿐이고({@code /v1/checkout/sessions} 생성·조회), 이 저장소의
 * PayPal 연동도 같은 방식이다. 의존성을 하나 늘리는 대신 쓰는 만큼만 붙인다.
 * <b>다만 웹훅 서명 검증만은 직접 구현했고</b>, 거기는 틀리면 곧바로 보안 구멍이라
 * {@link StripeWebhookVerifier} 에 따로 두고 테스트를 촘촘히 붙였다.
 *
 * <p>[JA] Stripe Checkout — <b>Stripe がホストする決済ページ</b>。
 *
 * <h3>[JA] なぜ Checkout(リダイレクト)なのか</h3>
 *
 * <p>[JA] カード番号が<b>こちらのページを一切通らない。</b>ブラウザは Stripe が作った URL へ移動して
 * 戻ってくるだけなので PCI の範囲が最小になり、フロントエンドに Stripe のキーを置く必要すらない —
 * PayPal で経験した「ビルド時に埋め込まれる client id」の問題が構造的に起きない。
 *
 * <h3>[JA] 🔒 金額はサーバーが決める</h3>
 *
 * <p>[JA] セッション作成に使う金額は<b>引数で受け取った注文の合計</b>である。ブラウザが送った値で
 * セッションを作ると決済金額そのものを客が決めることになり、後で突き合わせても手遅れになる — 利用者は
 * 自分が承認した金額を払ったと信じているからだ(docs/35)。
 *
 * <h3>[JA] SDK ではなく RestClient を使う理由</h3>
 *
 * <p>[JA] 使うエンドポイントは二つだけで({@code /v1/checkout/sessions} の作成 · 照会)、このリポジトリの
 * PayPal 連携も同じ方式である。依存を一つ増やす代わりに、使う分だけをつなぐ。
 * <b>ただし Webhook の署名検証だけは自前で実装した</b>ので、そこは間違えればそのままセキュリティホールになるため
 * {@link StripeWebhookVerifier} に分けて置き、テストを細かく付けた。
 */
@Slf4j
public class StripeCheckoutGateway implements HostedCheckoutGateway, RefundingGateway {

    /** Stripe 가 "결제됨" 이라고 말하는 값. 세션의 {@code payment_status} 필드다. */
    // [JA] Stripe が「支払い済み」と示す値。セッションの {@code payment_status} フィールドである。
    static final String PAID = "paid";

    private final RestClient http;
    private final String secretKey;
    /** 청구 통화가 주문에 없을 때(옛 주문)의 통화 — 상점 통화. */
    // [JA] 請求通貨が注文に無いとき(古い注文)の通貨 — ショップの通貨。
    private final String baseCurrency;

    public StripeCheckoutGateway(RestClient http, String secretKey) {
        this(http, secretKey, "USD");
    }

    public StripeCheckoutGateway(RestClient http, String secretKey, String baseCurrency) {
        this.http = http;
        this.secretKey = secretKey;
        this.baseCurrency = baseCurrency;
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.STRIPE;
    }

    @Override
    public CheckoutSession startCheckout(Order order, String returnUrl, String cancelUrl) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("mode", "payment");
        // 🔒 결제수단을 **코드가** 고정한다. 비워 두면 Stripe 는 대시보드에서 켜 둔 수단을
        //    전부 띄운다(동적 결제수단 — 2023 년부터 기본). 그때 누군가 대시보드에서
        //    コンビニ·銀行振込 같은 지연 결제수단을 켜면 `async_payment_failed` 를 처리하는
        //    코드 없이 그 수단이 열리고, 입금하지 않은 주문이 영원히 "확인 중" 에 남는다.
        //    지연 수단을 붙일 때는 여기와 웹훅 핸들러를 **함께** 고쳐야 한다(docs/49 §4).
        // [JA] 🔒 決済手段は**コードが**固定する。空にすると Stripe はダッシュボードで有効にした手段を
        //      すべて表示する(動的決済手段 — 2023 年から既定)。その状態で誰かがダッシュボードで
        //      コンビニ · 銀行振込のような後払い型の手段を有効にすると、`async_payment_failed` を処理する
        //      コードがないままその手段が開き、入金されない注文が永遠に「確認中」に残る。
        //      後払い型の手段を加えるときは、ここと Webhook ハンドラを**一緒に**直す必要がある(docs/49 §4)。
        form.add("payment_method_types[0]", "card");
        form.add("success_url", returnUrl);
        form.add("cancel_url", cancelUrl);
        // 주문을 되찾는 근거. 웹훅은 브라우저를 거치지 않으므로 **세션 안에** 들어 있어야 한다.
        // [JA] 注文を特定する手がかり。Webhook はブラウザを経由しないので、**セッションの中に**入っている必要がある。
        form.add("client_reference_id", order.getTrackingId());

        form.add("line_items[0][quantity]", "1");
        // 청구 통화·금액은 주문에 저장된 것 — 결제수단의 통화가 상점 통화와 다르면
        // (일본 테스트 JPY) 주문 생성 시점의 환율로 이미 정해져 있다.
        // [JA] 請求通貨 · 金額は注文に保存されたもの — 決済手段の通貨がショップの通貨と異なる場合
        //      (日本向けテストの JPY)、注文作成時点の為替レートですでに決まっている。
        String currency = currencyOf(order);
        form.add("line_items[0][price_data][currency]", currency.toLowerCase());
        // 🔒 금액은 주문에서만 온다. 최소 단위는 통화가 정한다 — JPY 에 ×100 하면 100배다.
        // [JA] 🔒 金額は注文からだけ来る。最小単位は通貨が決める — JPY に ×100 すると 100 倍になる。
        form.add("line_items[0][price_data][unit_amount]",
                String.valueOf(PaymentCurrencyPolicy.toMinorUnits(chargedAmountOf(order), currency)));
        // 품목을 낱개로 보내지 않는 이유: 그러면 합계가 Stripe 쪽 계산에 좌우된다.
        // 결제 금액은 서버가 정한 하나의 숫자여야 하고, 상세는 주문 화면이 보여 준다.
        // [JA] 品目を一つずつ送らない理由: そうすると合計が Stripe 側の計算に左右される。
        //      決済金額はサーバーが決めた一つの数字でなければならず、明細は注文画面が表示する。
        form.add("line_items[0][price_data][product_data][name]",
                "주문 " + order.getOrderId());

        JsonNode session = post("/v1/checkout/sessions", form,
                "Stripe 결제 페이지를 열지 못했습니다.");

        String id = session.path("id").asText("");
        String url = session.path("url").asText("");
        if (id.isBlank() || url.isBlank()) {
            throw new PaymentVerificationException("Stripe 세션 응답이 비어 있습니다.");
        }

        log.info("Stripe 결제 세션 생성: orderId={} sessionId={}", order.getOrderId(), id);
        return new CheckoutSession(id, url);
    }

    @Override
    public VerifiedPayment verify(String sessionId) {
        JsonNode session;
        try {
            session = http.get()
                    .uri("/v1/checkout/sessions/{id}", sessionId)
                    .header("Authorization", "Bearer " + secretKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            // 404 도 여기로 온다 — 존재하지 않는 세션 id 를 지어내면 그렇게 된다.
            // ⚠️ sessionId 는 로그에 남겨도 된다(비밀이 아니다). secretKey 는 절대 안 된다.
            // [JA] 404 もここに来る — 存在しないセッション id をでっち上げるとそうなる。
            //      ⚠️ sessionId はログに残してよい(秘密ではない)。secretKey は絶対に残さない。
            throw new PaymentVerificationException("Stripe 세션 조회 실패: " + sessionId, e);
        }

        if (session == null) {
            throw new PaymentVerificationException("Stripe 응답이 비어 있습니다: " + sessionId);
        }

        // ⚠️ status(complete/open/expired) 가 아니라 **payment_status** 를 본다.
        //    status=complete 는 "세션이 끝났다"일 뿐이고, 무료 주문이나 나중 결제에서는
        //    돈이 오지 않은 채로도 complete 가 된다.
        // [JA] ⚠️ status(complete/open/expired)ではなく **payment_status** を見る。
        //      status=complete は「セッションが終わった」というだけで、無料の注文や後払いでは
        //      入金がないままでも complete になる。
        String paymentStatus = session.path("payment_status").asText("");
        String currency = session.path("currency").asText("");
        long amountTotal = session.path("amount_total").asLong(-1);

        if (amountTotal < 0) {
            throw new PaymentVerificationException(
                    "Stripe 응답에 금액이 없습니다: " + sessionId + " (payment_status=" + paymentStatus + ")");
        }

        return new VerifiedPayment(
                PaymentProvider.STRIPE,
                sessionId,
                PAID.equals(paymentStatus),
                paymentStatus,
                // 응답의 통화로 자릿수를 정한다 — JPY 1500 은 1500 이지 15.00 이 아니다.
                // [JA] 応答の通貨で桁数を決める — JPY 1500 は 1500 であって 15.00 ではない。
                PaymentCurrencyPolicy.fromMinorUnits(amountTotal, currency),
                currency.toUpperCase(),
                blankToNull(session.path("client_reference_id").asText("")));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * 환불.
     *
     * <p>Stripe 는 세션이 아니라 <b>PaymentIntent</b> 를 환불한다. 세션을 조회해
     * {@code payment_intent} 를 찾고 {@code POST /v1/refunds} 를 부른다. 금액은 명시한다 —
     * 비워 보내면 "남은 전액" 인데, 환불액은 DB 의 주문 총액이 정해야 한다.
     *
     * <p>응답의 {@code status} 는 카드면 대개 곧바로 {@code succeeded} 이고, 은행 처리가
     * 필요하면 {@code pending} 이다. 둘 다 "받아들여졌다" 다. {@code failed}·{@code canceled}
     * 만 거절이다.
     *
     * <p>[JA] 返金。
     *
     * <p>[JA] Stripe はセッションではなく <b>PaymentIntent</b> を返金する。セッションを照会して
     * {@code payment_intent} を見つけ、{@code POST /v1/refunds} を呼ぶ。金額は明示する —
     * 空で送ると「残り全額」になるが、返金額は DB の注文合計が決めるべきである。
     *
     * <p>[JA] 応答の {@code status} はカードならたいていすぐに {@code succeeded} になり、銀行の処理が
     * 必要なら {@code pending} になる。どちらも「受け付けられた」である。{@code failed} · {@code canceled}
     * だけが拒否である。
     */
    @Override
    public RefundResult refund(String sessionId, BigDecimal amount, String currency, String reason,
            String refundKey) {
        JsonNode session;
        try {
            session = http.get()
                    .uri("/v1/checkout/sessions/{id}", sessionId)
                    .header("Authorization", "Bearer " + secretKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new PaymentVerificationException("Stripe 세션 조회 실패: " + sessionId, e);
        }
        String paymentIntent = session == null ? "" : session.path("payment_intent").asText("");
        if (paymentIntent.isBlank()) {
            // 결제 전 세션에는 payment_intent 가 없다 = 돈이 오지 않았다.
            // [JA] 決済前のセッションには payment_intent がない = 入金されていない。
            throw new PaymentVerificationException(
                    "Stripe 세션에 결제가 없어 환불할 수 없습니다: " + sessionId);
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("payment_intent", paymentIntent);
        form.add("amount", String.valueOf(PaymentCurrencyPolicy.toMinorUnits(amount, currency)));
        // Stripe 가 받는 사유는 셋 중 하나다. 우리 사유는 metadata 로 남긴다.
        // [JA] Stripe が受け付ける理由は三つのうちの一つ。こちらの理由は metadata に残す。
        form.add("reason", "requested_by_customer");
        form.add("metadata[session_id]", sessionId);
        if (reason != null && !reason.isBlank()) {
            form.add("metadata[note]", reason.length() > 200 ? reason.substring(0, 200) : reason);
        }

        // Idempotency-Key: 같은 환불이 두 번 가도 한 번만. 부분 환불마다 키가 다르다.
        // [JA] Idempotency-Key: 同じ返金が二回送られても一回だけ。部分返金ごとにキーが異なる。
        JsonNode refund = post("/v1/refunds", form, "Stripe 환불 요청에 실패했습니다.",
                refundKey == null || refundKey.isBlank() ? null : "refund-" + sessionId + "-" + refundKey);

        long refunded = refund.path("amount").asLong(-1);
        if (refunded < 0) {
            throw new PaymentVerificationException(
                    "Stripe 환불 응답에 금액이 없습니다: " + sessionId
                            + " (status=" + refund.path("status").asText("") + ")");
        }
        String status = refund.path("status").asText("");
        boolean accepted = "succeeded".equals(status) || "pending".equals(status);

        log.info("Stripe 환불: sessionId={} refundId={} status={}", sessionId,
                refund.path("id").asText(""), status);
        String refundedCurrency = refund.path("currency").asText("").toUpperCase();
        return new RefundResult(
                PaymentProvider.STRIPE,
                refund.path("id").asText(""),
                accepted,
                status,
                PaymentCurrencyPolicy.fromMinorUnits(refunded,
                        refundedCurrency.isBlank() ? currency : refundedCurrency),
                refundedCurrency);
    }

    /** 세션의 {@code client_reference_id} — 웹훅이 어느 주문인지 알아내는 근거다. */
    // [JA] セッションの {@code client_reference_id} — Webhook がどの注文かを突き止める手がかりである。
    public String trackingIdOf(String sessionId) {
        JsonNode session;
        try {
            session = http.get()
                    .uri("/v1/checkout/sessions/{id}", sessionId)
                    .header("Authorization", "Bearer " + secretKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new PaymentVerificationException("Stripe 세션 조회 실패: " + sessionId, e);
        }
        String trackingId = (session == null) ? "" : session.path("client_reference_id").asText("");
        if (trackingId.isBlank()) {
            throw new PaymentVerificationException(
                    "Stripe 세션에 주문 참조가 없습니다: " + sessionId);
        }
        return trackingId;
    }

    private JsonNode post(String path, MultiValueMap<String, String> form, String failureMessage) {
        return post(path, form, failureMessage, null);
    }

    private JsonNode post(String path, MultiValueMap<String, String> form, String failureMessage,
            String idempotencyKey) {
        try {
            var request = http.post()
                    .uri(path)
                    .header("Authorization", "Bearer " + secretKey);
            if (idempotencyKey != null) {
                request = request.header("Idempotency-Key", idempotencyKey);
            }
            return request
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.error("Stripe 호출 실패: {}", path, e);
            throw new PaymentVerificationException(failureMessage, e);
        }
    }

    /** 청구 통화 — 주문에 저장된 것, 없으면(옛 주문) 상점 통화. */
    // [JA] 請求通貨 — 注文に保存されたもの。無ければ(古い注文)ショップの通貨。
    private String currencyOf(Order order) {
        return order.getCurrency() == null || order.getCurrency().isBlank()
                ? baseCurrency : order.getCurrency();
    }

    /** 청구 금액 — 주문에 저장된 것, 없으면(옛 주문) 상점 통화의 총액. */
    // [JA] 請求金額 — 注文に保存されたもの。無ければ(古い注文)ショップ通貨での合計。
    private static BigDecimal chargedAmountOf(Order order) {
        return order.getChargedAmount() != null ? order.getChargedAmount() : order.getTotal();
    }
}
