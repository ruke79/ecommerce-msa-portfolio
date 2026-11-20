package com.project.order_service.payment.portone;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.project.common.constants.PaymentProvider;
import com.project.order_service.payment.RefundResult;
import com.project.order_service.payment.RefundingGateway;
import com.project.order_service.payment.PaymentVerificationException;
import com.project.order_service.payment.VerifiedPayment;

import lombok.extern.slf4j.Slf4j;

/**
 * 포트원(구 아임포트) V1 — 국내 PG·간편결제 어그리게이터.
 *
 * <h3>왜 {@code HostedCheckoutGateway} 가 아닌가</h3>
 *
 * <p>포트원은 <b>브라우저가 결제창을 연다</b>({@code IMP.request_pay}). 서버가 세션을
 * 먼저 만드는 Stripe Checkout 과 달리, PayPal 과 같은 모양이다 — 그래서 시작 단계가
 * 없는 {@link PaymentGateway} 를 구현한다. <b>포트가 둘로 갈려 있는 것이 여기서 값을
 * 한다</b>: 제공자마다 시작 방식이 다른데 확인 규칙은 하나로 유지된다.
 *
 * <h3>🔒 브라우저가 금액을 정해도 괜찮은 이유</h3>
 *
 * <p>{@code IMP.request_pay} 에는 금액이 들어간다. 즉 <b>브라우저가 결제 금액을 정한다.</b>
 * 그래도 되는 이유는 확인 단계에서 <b>포트원이 알려 준 실제 결제액을 DB 의 주문 총액과
 * 대조</b>하기 때문이다({@code OrderDataService.recordPayment}). 사용자가 1원으로 바꿔
 * 결제하면 그 결제는 성공하지만 <b>주문에는 반영되지 않는다.</b>
 *
 * <p>⚠️ 그래서 이 대조를 빼면 즉시 무료 주문이 가능해진다. 포트를 만든 이유가 이
 * 규칙을 제공자마다 다시 쓰지 않기 위해서다.
 *
 * <h3>⚠️ 이 코드는 실제 API 로 검증하지 못했다</h3>
 *
 * <p>문서 규격대로 작성했다. 응답 필드 매핑이 한 곳({@link #toVerified})에 모여 있으니
 * 실제 응답과 다르면 거기만 고치면 된다. 기동·검증 실패 로그가 무엇을 받았는지 남긴다.
 *
 * <p>[JA] PortOne(旧アイムポート) V1 — 国内 PG・簡易決済アグリゲータ。
 * <b>なぜ {@code HostedCheckoutGateway} ではないのか</b>: PortOne は<b>ブラウザが決済画面を開く</b>({@code IMP.request_pay})。
 * サーバが先にセッションを作る Stripe Checkout と違い、PayPal と同じ形である — そのため開始段階の
 * ない {@link PaymentGateway} を実装する。<b>ポートが二つに分かれていることがここで効く</b>:
 * プロバイダごとに開始方式が違っても、確認ルールは一つに保たれる。
 * <b>🔒 ブラウザが金額を決めても大丈夫な理由</b>: {@code IMP.request_pay} には金額が入る。つまり<b>ブラウザが決済金額を決める。</b>
 * それでもよいのは、確認段階で<b>PortOne が知らせた実際の決済額を DB の注文総額と
 * 照合</b>するからである({@code OrderDataService.recordPayment})。ユーザーが 1 ウォンに変えて
 * 決済すると、その決済は成功するが<b>注文には反映されない。</b>
 * ⚠️ だからこの照合を外すと即座に無料注文が可能になる。ポートを作った理由は、この
 * ルールをプロバイダごとに書き直さないためである。
 * <b>⚠️ このコードは実際の API で検証できていない</b>: ドキュメントの仕様どおりに書いた。応答フィールドのマッピングが一か所({@link #toVerified})に集まっているので、
 * 実際の応答と違えばそこだけ直せばよい。起動・検証失敗のログが何を受け取ったかを残す。
 */
@Slf4j
public class PortOnePaymentGateway implements PortOneGateway {

    /** 포트원이 "결제 완료" 라고 말하는 값. ready(가상계좌 발급)·cancelled·failed 가 나머지다. */
    // [JA] PortOne が「決済完了」と言う値。ready(仮想口座発行)・cancelled・failed が残りである。
    static final String PAID = "paid";

    /** 토큰 만료 직전에 쓰다 401 을 맞지 않도록 미리 갱신한다(PayPal 쪽과 같은 이유). */
    // [JA] トークン期限切れの直前に使って 401 を受けないよう、先に更新する(PayPal 側と同じ理由)。
    private static final Duration TOKEN_SAFETY_MARGIN = Duration.ofMinutes(1);

    private final RestClient http;
    private final String apiKey;
    private final String apiSecret;

    private volatile String cachedToken;
    private volatile Instant cachedTokenExpiry = Instant.EPOCH;

    public PortOnePaymentGateway(RestClient http, String apiKey, String apiSecret) {
        this.http = http;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.PORTONE;
    }

    @Override
    public VerifiedPayment verify(String impUid) {
        return toVerified(impUid, fetchPayment(impUid));
    }

    @Override
    public String trackingIdOf(String paymentRef) {
        return merchantUidOf(paymentRef);
    }

    /**
     * V1 웹훅은 결제 상태가 바뀔 때마다 {@code imp_uid} 만 실어 보낸다 — 어떤 상태인지는
     * 본문으로 알 수 없고 알 필요도 없다. 어차피 다시 조회한다.
     *
     * <p>[JA] V1 Webhook は決済状態が変わるたびに {@code imp_uid} だけを載せて送る — どの状態かは
     * 本文からは分からず、知る必要もない。どのみち再照会する。
     */
    @Override
    public java.util.Optional<String> paidPaymentRefOf(com.fasterxml.jackson.databind.JsonNode body) {
        String impUid = body.path("imp_uid").asText("");
        return impUid.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(impUid);
    }

    /** V1 웹훅은 상태를 말하지 않는다 — 발급 알림도 결제 완료 알림과 같은 모양이다. */
    // [JA] V1 Webhook は状態を言わない — 発行通知も決済完了通知と同じ形である。
    @Override
    public java.util.Optional<String> issuedPaymentRefOf(com.fasterxml.jackson.databind.JsonNode body) {
        return paidPaymentRefOf(body);
    }

    /** V1 이 "가상계좌가 발급됐고 아직 입금 전" 이라고 말하는 값. */
    // [JA] V1 が「仮想口座が発行され、まだ入金前」と言う値。
    static final String READY = "ready";
    static final String PAY_METHOD_VBANK = "vbank";

    /**
     * 발급된 가상계좌 — {@code status=ready} 이고 {@code pay_method=vbank} 일 때만.
     * {@code vbank_date} 는 유닉스 초다.
     *
     * <p>[JA] 発行された仮想口座 — {@code status=ready} かつ {@code pay_method=vbank} の時だけ。
     * {@code vbank_date} は Unix 秒である。
     */
    @Override
    public java.util.Optional<VirtualAccount> virtualAccountOf(String impUid) {
        JsonNode payment = fetchPayment(impUid);
        if (!READY.equals(payment.path("status").asText(""))
                || !PAY_METHOD_VBANK.equals(payment.path("pay_method").asText(""))) {
            return java.util.Optional.empty();
        }
        String account = payment.path("vbank_num").asText("");
        if (account.isBlank()) {
            throw new PaymentVerificationException("포트원 가상계좌 응답에 계좌번호가 없습니다: " + impUid);
        }
        long due = payment.path("vbank_date").asLong(0);
        return java.util.Optional.of(new VirtualAccount(
                payment.path("vbank_name").asText(""),
                account,
                payment.path("vbank_holder").asText(""),
                due > 0 ? java.time.LocalDateTime.ofInstant(Instant.ofEpochSecond(due), java.time.ZoneId.systemDefault()) : null,
                new BigDecimal(payment.path("amount").asText("0")),
                payment.path("currency").asText("KRW").toUpperCase()));
    }

    /** 결제에 붙은 우리 주문 참조({@code merchant_uid}) — 웹훅이 어느 주문인지 아는 근거. */
    // [JA] 決済に付いた当方の注文参照({@code merchant_uid}) — Webhook がどの注文かを知る根拠。
    public String merchantUidOf(String impUid) {
        String merchantUid = fetchPayment(impUid).path("merchant_uid").asText("");
        if (merchantUid.isBlank()) {
            throw new PaymentVerificationException("포트원 결제에 주문 참조가 없습니다: " + impUid);
        }
        return merchantUid;
    }

    /**
     * 환불 — V1 {@code POST /payments/cancel}.
     *
     * <p>{@code amount} 를 명시한다(비우면 전액이지만, 전액이 얼마인지는 우리 DB 가 정한다).
     * 응답의 {@code status} 는 전액 취소면 {@code cancelled}, 그리고 {@code cancel_amount}
     * 에 누적 취소액이 온다. {@code code != 0} 이 거절이다 — 조회와 같은 규칙이다.
     *
     * <p>{@code checksum} 은 보내지 않는다. 그 값은 "취소 가능한 잔액이 이 값이어야 한다"
     * 는 동시성 가드인데, 우리는 주문당 환불 1회를 DB 상태로 막는다.
     *
     * <p>[JA] 返金 — V1 {@code POST /payments/cancel}。
     * {@code amount} を明示する(空なら全額だが、全額がいくらかは当方の DB が決める)。
     * 応答の {@code status} は全額取消なら {@code cancelled}、そして {@code cancel_amount}
     * に累積取消額が来る。{@code code != 0} が拒絶である — 照会と同じルールである。
     * {@code checksum} は送らない。その値は「取消可能な残高がこの値でなければならない」
     * という同時実行ガードだが、当方は注文あたり返金 1 回を DB の状態で防ぐ。
     */
    @Override
    public RefundResult refund(String impUid, BigDecimal amount, String currency, String reason,
            String refundKey) {   // 포트원 V1 에는 멱등 헤더가 없다 — 키는 쓰지 않는다 ／ [JA] PortOne V1 には冪等ヘッダがない — キーは使わない
        return refund(impUid, amount, currency, reason, refundKey, null);
    }

    /**
     * 포트원 V1 취소(전액·부분).
     *
     * <h3>🐛 부분 환불이 "실패" 로 읽혀 <b>여러 번 나갔다</b></h3>
     *
     * <p>예전에는 {@code status == "cancelled"} 일 때만 받아들였다. V1 은 <b>부분 취소 뒤에도 {@code paid}</b> 다 — 돈은
     * 나갔는데 거부로 읽었고, 호출자는 예외를 던졌다. 재고 부족 자동 환불은 Kafka 가 세 번 더 다시 보냈고, V1 에는 멱등 헤더가
     * 없어 <b>보낼 때마다 실제로 또 환불됐다.</b> 두 번째 부분 환불부터는 누적 취소액({@code cancel_amount})을 이번 환불액으로
     * 읽어 "금액이 다르다" 로 또 실패했다.
     *
     * <ul>
     *   <li>{@code checksum} — 취소 직전 환불 가능 잔액(우리 기록). 포트원 잔액과 다르면 취소를 거절한다. 앞선 시도가 포트원에서는
     *       성공했는데 우리 쪽이 실패로 끝나 다시 보내면 잔액이 이미 줄어 있어 <b>두 번째가 나가지 않는다.</b></li>
     *   <li>받아들임은 {@code code == 0} — 실패는 포트원이 code 로 말한다. 상태는 전액이면 cancelled, 부분이면 paid 다.</li>
     *   <li>이번 환불액은 {@code cancel_history} 의 <b>가장 최근 건</b>이다(누적값이 아니라).</li>
     * </ul>
     *
     * <p>[JA] PortOne V1 の取消(全額・部分)。
     * <b>🐛 部分返金が「失敗」と読まれ、<b>何度も出ていった</b></b>:
     * 以前は {@code status == "cancelled"} の時だけ受け付けていた。V1 は<b>部分取消の後も {@code paid}</b> である — お金は
     * 出ていったのに拒否と読み、呼び出し側は例外を投げた。在庫不足の自動返金は Kafka がさらに三回再送し、V1 には冪等ヘッダが
     * ないので<b>送るたびに実際にまた返金された。</b>二回目の部分返金からは累積取消額({@code cancel_amount})を今回の返金額として
     * 読み「金額が違う」でまた失敗した。
     * {@code checksum} — 取消直前の返金可能残高(当方の記録)。PortOne の残高と違えば取消を拒絶する。先の試行が PortOne では
     * 成功したのに当方が失敗で終わって再送すると、残高が既に減っているので<b>二回目は出ていかない。</b>
     * 受け付けは {@code code == 0} — 失敗は PortOne が code で言う。状態は全額なら cancelled、部分なら paid である。
     * 今回の返金額は {@code cancel_history} の<b>最新の一件</b>である(累積値ではなく)。
     */
    @Override
    public RefundResult refund(String impUid, BigDecimal amount, String currency, String reason,
            String refundKey, BigDecimal refundableBefore) {
        String body = "{\"imp_uid\":\"" + impUid.replace("\"", "")
                + "\",\"amount\":" + amount.toPlainString()
                + (refundableBefore == null ? "" : ",\"checksum\":" + refundableBefore.toPlainString())
                + ",\"reason\":\"" + (reason == null ? "" : reason.replace("\"", "'")) + "\"}";

        JsonNode response;
        try {
            response = http.post()
                    .uri("/payments/cancel")
                    .header("Authorization", accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new PaymentVerificationException("포트원 환불 요청 실패: " + impUid, e);
        }
        if (response == null) {
            throw new PaymentVerificationException("포트원 환불 응답이 비어 있습니다: " + impUid);
        }
        int code = response.path("code").asInt(-1);
        if (code != 0) {
            // checksum 불일치(이미 취소된 환불을 다시 보냄)도 여기로 온다 — 돈은 나가지 않았다.
            // [JA] checksum 不一致(既に取消された返金を再送)もここに来る — お金は出ていっていない。
            throw new PaymentVerificationException(
                    "포트원이 환불을 거부했습니다: code=" + code
                            + ", message=" + response.path("message").asText(""));
        }

        JsonNode payment = response.path("response");
        String status = payment.path("status").asText("");
        JsonNode latest = latestCancel(payment.path("cancel_history"));
        BigDecimal refunded;
        String refundRef;
        if (latest != null) {
            refunded = new BigDecimal(latest.path("amount").asText("0"));
            // 부분 환불이 여럿이면 imp_uid 만으로는 구분되지 않는다 — 취소 건의 PG 거래 id(없으면 시각)를 붙인다.
            // [JA] 部分返金が複数あると imp_uid だけでは区別できない — 取消の PG 取引 id(なければ時刻)を付ける。
            String tid = latest.path("pg_tid").asText("");
            refundRef = impUid + ":" + (tid.isBlank() ? latest.path("cancelled_at").asText("") : tid);
        } else {
            // 이력이 없는 응답 — 누적 취소액밖에 없다. 첫 환불이면 그것이 이번 환불액이다(아니면 호출자가 금액 불일치로 멈춘다).
            // [JA] 履歴のない応答 — 累積取消額しかない。初回返金ならそれが今回の返金額である(そうでなければ呼び出し側が金額不一致で止まる)。
            JsonNode cancelAmount = payment.path("cancel_amount");
            if (cancelAmount.isMissingNode() || cancelAmount.isNull()) {
                throw new PaymentVerificationException(
                        "포트원 환불 응답에 취소액이 없습니다: " + impUid + " (status=" + status + ")");
            }
            refunded = new BigDecimal(cancelAmount.asText("0"));
            refundRef = impUid;
        }

        log.info("포트원 환불: impUid={} status={} amount={}", impUid, status, refunded.toPlainString());
        return new RefundResult(
                PaymentProvider.PORTONE,
                refundRef,
                "cancelled".equals(status) || "paid".equals(status),
                status,
                refunded,
                payment.path("currency").asText("").toUpperCase());
    }

    /** 취소 이력 중 가장 최근 건(cancelled_at 이 가장 큰 것). 이력이 없으면 null. */
    // [JA] 取消履歴のうち最新の一件(cancelled_at が最大のもの)。履歴がなければ null。
    private static JsonNode latestCancel(JsonNode history) {
        if (history == null || !history.isArray() || history.isEmpty()) {
            return null;
        }
        JsonNode latest = null;
        for (JsonNode h : history) {
            if (latest == null || h.path("cancelled_at").asLong(0) >= latest.path("cancelled_at").asLong(0)) {
                latest = h;
            }
        }
        return latest;
    }

    // ------------------------------------------------------------------ 조회
    // [JA] 照会

    private JsonNode fetchPayment(String impUid) {
        JsonNode body;
        try {
            body = http.get()
                    .uri("/payments/{impUid}", impUid)
                    // ⚠️ V1 은 Bearer 접두사 없이 토큰 자체를 넣는다.
                    // [JA] ⚠️ V1 は Bearer 接頭辞なしでトークンそのものを入れる。
                    .header("Authorization", accessToken())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            // 404 도 여기로 온다 — 존재하지 않는 imp_uid 를 지어내면 그렇게 된다.
            // ⚠️ impUid 는 로그에 남겨도 된다(비밀이 아니다). 토큰·시크릿은 절대 안 된다.
            // [JA] 404 もここに来る — 存在しない imp_uid をでっち上げるとそうなる。
            //      ⚠️ impUid はログに残してよい(秘密ではない)。トークン・シークレットは絶対にだめ。
            throw new PaymentVerificationException("포트원 결제 조회 실패: " + impUid, e);
        }

        if (body == null) {
            throw new PaymentVerificationException("포트원 응답이 비어 있습니다: " + impUid);
        }
        // 포트원은 HTTP 200 에 code != 0 으로 실패를 담는다. 상태 코드만 보면 놓친다.
        // [JA] PortOne は HTTP 200 に code != 0 で失敗を載せる。ステータスコードだけ見ると見逃す。
        int code = body.path("code").asInt(-1);
        if (code != 0) {
            throw new PaymentVerificationException(
                    "포트원이 조회를 거부했습니다: code=" + code
                            + ", message=" + body.path("message").asText(""));
        }
        return body.path("response");
    }

    /**
     * 포트원 응답 → 제공자 중립 결과.
     *
     * <p><b>실제 응답과 어긋나면 여기만 고치면 된다.</b> 매핑을 한 곳에 모아 둔 이유다.
     *
     * <p>[JA] PortOne 応答 → プロバイダ中立の結果。
     * <b>実際の応答とずれたらここだけ直せばよい。</b>マッピングを一か所に集めた理由である。
     */
    private VerifiedPayment toVerified(String impUid, JsonNode payment) {
        String status = payment.path("status").asText("");
        String currency = payment.path("currency").asText("");
        // amount 는 최소 단위가 아니라 **실제 금액**이다(KRW 1000 = 1000).
        // [JA] amount は最小単位ではなく**実際の金額**である(KRW 1000 = 1000)。
        JsonNode amountNode = payment.path("amount");

        if (amountNode.isMissingNode() || amountNode.isNull()) {
            throw new PaymentVerificationException(
                    "포트원 응답에 금액이 없습니다: " + impUid + " (status=" + status + ")");
        }

        return new VerifiedPayment(
                PaymentProvider.PORTONE,
                impUid,
                PAID.equals(status),
                status,
                new BigDecimal(amountNode.asText("0")),
                currency.toUpperCase(),
                payment.path("merchant_uid").asText("").isBlank() ? null : payment.path("merchant_uid").asText(""));
    }

    // ------------------------------------------------------------------ 토큰
    // [JA] トークン

    private String accessToken() {
        String token = cachedToken;
        if (token != null && Instant.now().isBefore(cachedTokenExpiry)) {
            return token;
        }
        return refreshToken();
    }

    private synchronized String refreshToken() {
        // 다른 스레드가 방금 갱신했을 수 있다.
        // [JA] 別のスレッドがちょうど更新したかもしれない。
        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiry)) {
            return cachedToken;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("imp_key", apiKey);
        form.add("imp_secret", apiSecret);

        JsonNode body;
        try {
            body = http.post()
                    .uri("/users/getToken")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            // ⚠️ 예외 메시지에 시크릿이 실리지 않도록 원문을 붙이지 않는다.
            // [JA] ⚠️ 例外メッセージにシークレットが載らないよう、原文を付けない。
            throw new PaymentVerificationException("포트원 인증에 실패했습니다.", e);
        }

        if (body == null || body.path("code").asInt(-1) != 0) {
            throw new PaymentVerificationException("포트원 인증에 실패했습니다 — 키를 확인하세요.");
        }

        JsonNode response = body.path("response");
        String token = response.path("access_token").asText("");
        long expiredAt = response.path("expired_at").asLong(0);
        if (token.isBlank()) {
            throw new PaymentVerificationException("포트원 토큰 응답이 비어 있습니다.");
        }

        cachedToken = token;
        cachedTokenExpiry = (expiredAt > 0)
                ? Instant.ofEpochSecond(expiredAt).minus(TOKEN_SAFETY_MARGIN)
                // expired_at 이 없으면 보수적으로 짧게 잡는다(V1 기본 만료는 30분이다).
                // [JA] expired_at がなければ保守的に短く取る(V1 のデフォルト有効期限は 30 分である)。
                : Instant.now().plus(Duration.ofMinutes(20));
        return token;
    }
}
