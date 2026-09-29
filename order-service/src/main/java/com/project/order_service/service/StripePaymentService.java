package com.project.order_service.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.constants.PaymentProvider;
import com.project.order_service.model.Order;
import com.project.order_service.payment.CheckoutSession;
import com.project.order_service.payment.PaymentVerificationException;
import com.project.order_service.payment.stripe.StripeCheckoutGateway;
import com.project.order_service.payment.stripe.StripeWebhookVerifier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Stripe 결제의 시작과 확정.
 *
 * <h3>확정 경로가 둘이고, 둘 다 같은 곳으로 모인다</h3>
 *
 * <ol>
 *   <li><b>복귀 검증</b> — 결제 후 브라우저가 돌아오면 서버가 Stripe 에 조회한다.
 *       사용자가 즉시 결과를 본다.</li>
 *   <li><b>웹훅</b> — Stripe 가 직접 알린다. <b>브라우저가 돌아오지 않아도</b> 반영된다.</li>
 * </ol>
 *
 * <p>왜 둘 다 필요한가: 복귀 검증만 두면 결제 직후 탭을 닫은 사용자는 <b>돈은 빠졌는데
 * 주문은 미결제</b>로 남는다. 웹훅만 두면 결제 직후 화면이 "확인 중" 에 머문다.
 *
 * <p>둘이 겹치는 것은 <b>정상이고 자주 일어난다.</b> 그래서 확정은
 * {@code OrderDataService.recordPayment} 하나로 모으고, 그쪽이 멱등하다 — 같은 결제가
 * 이미 붙어 있으면 조용히 끝낸다. 멱등성을 여기 두지 않은 이유는 그것이 <b>모든 제공자에
 * 공통인 규칙</b>이기 때문이다(PaymentGateway 주석).
 *
 * <h3>🐛 {@code stripe.enabled=false} 면 이 빈도 없어야 한다</h3>
 *
 * <p>게이트웨이·검증기는 {@code StripeConfig} 가 그 조건으로 만든다. 그런데 이 서비스에는
 * 조건이 없어서, Stripe 를 끈 환경(클러스터 기본값)에서 <b>order-service 가 기동조차 못
 * 했다</b> — "No qualifying bean of type StripeCheckoutGateway". 컨트롤러는 이미
 * {@code @Autowired(required = false)} 로 null 을 기다리고 있었으니, 빠진 것은 이 한 줄이다.
 * 같은 조건을 여기에도 둔다({@code PortOnePaymentService} 도 같다).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "stripe.enabled", havingValue = "true")
@RequiredArgsConstructor
public class StripePaymentService {

    /** Stripe 가 "결제가 끝났다" 고 알리는 이벤트. 다른 이벤트는 무시한다. */
    private static final String CHECKOUT_COMPLETED = "checkout.session.completed";
    /** 나중 결제(계좌이체 등)가 뒤늦게 성공했을 때. 같은 처리를 한다. */
    private static final String ASYNC_PAYMENT_SUCCEEDED = "checkout.session.async_payment_succeeded";

    private final OrderCacheService cahcedOrderService;
    private final OrderDataService orderDataService;
    private final StripeCheckoutGateway stripeGateway;
    private final StripeWebhookVerifier webhookVerifier;
    private final ObjectMapper objectMapper;

    @Value("${frontend.url}")
    private String frontendUrl;

    /**
     * 결제 페이지를 연다.
     *
     * <p>⚠️ <b>트랜잭션을 열지 않는다.</b> Stripe 호출이 들어 있다 — DB 커넥션을 쥔 채
     * 외부 서비스를 기다리면 안 된다(docs/37 #5).
     *
     * @param requesterId 게이트웨이가 검증한 요청자. 남의 주문으로 결제 페이지를 열 수 없다
     * @return 브라우저를 보낼 주소
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CheckoutSession startCheckout(String trackingId, Long requesterId) {
        Order order = orderDataService.getOrderByTrackingId(trackingId);
        if (order == null) {
            throw new PaymentVerificationException("주문을 찾을 수 없습니다: trackingId=" + trackingId);
        }

        if (requesterId == null || !requesterId.equals(order.getCustomerId())) {
            log.warn("다른 고객의 주문으로 결제를 시작하려는 시도를 차단: orderId={}", order.getOrderId());
            // 404 와 같은 문구 — 403 은 "그 주문은 존재한다"를 알려 준다.
            throw new PaymentVerificationException("주문을 찾을 수 없습니다: trackingId=" + trackingId);
        }

        if (order.getProviderPaymentId() != null) {
            throw new PaymentVerificationException("이미 결제된 주문입니다.");
        }

        // 복귀 주소에 세션 id 를 실어 보낸다. Stripe 가 {CHECKOUT_SESSION_ID} 를 치환한다 —
        // 우리가 미리 알 수 없는 값이라 이 자리표시자가 규격으로 정해져 있다.
        String orderPath = frontendUrl + "/order/" + order.getOrderId();
        String returnUrl = orderPath + "?stripe=success&session_id={CHECKOUT_SESSION_ID}";
        String cancelUrl = orderPath + "?stripe=canceled";

        return stripeGateway.startCheckout(order, returnUrl, cancelUrl);
    }

    /**
     * 복귀 검증 — 브라우저가 돌아왔다.
     *
     * <p>브라우저가 보고한 것은 <b>세션 id 하나뿐</b>이고, 그마저 믿지 않는다. 그 세션을
     * Stripe 에 조회해 금액까지 대조하는 것은 {@code recordPayment} 다.
     */
    public void confirmReturn(String trackingId, String sessionId, Long requesterId) {
        cahcedOrderService.recordPayment(trackingId, PaymentProvider.STRIPE, sessionId, requesterId);
    }

    /**
     * 웹훅 — Stripe 가 직접 알렸다.
     *
     * <h3>🔒 이 경로에는 로그인한 사용자가 없다</h3>
     *
     * <p>그래서 두 가지가 복귀 검증과 다르다.
     *
     * <ul>
     *   <li><b>신원을 서명이 대신한다.</b> 인증 없이 열린 엔드포인트라 "정말 Stripe 가
     *       보냈는가" 는 서명만이 답한다({@link StripeWebhookVerifier}).</li>
     *   <li><b>주문 참조도 세션에서 읽는다.</b> 본문의 값을 그대로 쓰지 않고 Stripe 에 다시
     *       물어본다 — 서명이 유효해도 본문은 Stripe 가 만든 것이므로 괜찮지만, 어느 주문에
     *       붙일지는 <b>항상 제공자에게 확인한 값</b>으로 정하는 편이 규칙이 하나로 남는다.</li>
     * </ul>
     *
     * <p>소유권 검사는 결제를 시작한 사람이 이미 통과했다({@link #startCheckout}). 여기서는
     * 그 주문의 고객을 그대로 쓴다.
     */
    public void handleWebhook(String payload, String signatureHeader) {
        webhookVerifier.verify(payload, signatureHeader);

        JsonNode event;
        try {
            event = objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new PaymentVerificationException("Stripe 웹훅 본문을 읽지 못했습니다.", e);
        }

        String type = event.path("type").asText("");
        if (!CHECKOUT_COMPLETED.equals(type) && !ASYNC_PAYMENT_SUCCEEDED.equals(type)) {
            // 구독하지 않은 이벤트가 오는 것은 정상이다(대시보드 설정에 따라). 200 으로
            // 끝내야 Stripe 가 재시도하지 않는다.
            log.debug("Stripe 웹훅: 처리하지 않는 이벤트 {}", type);
            return;
        }

        String sessionId = event.path("data").path("object").path("id").asText("");
        if (sessionId.isBlank()) {
            throw new PaymentVerificationException("Stripe 웹훅에 세션 id 가 없습니다.");
        }

        String trackingId = stripeGateway.trackingIdOf(sessionId);
        Order order = orderDataService.getOrderByTrackingId(trackingId);
        if (order == null) {
            throw new PaymentVerificationException("주문을 찾을 수 없습니다: trackingId=" + trackingId);
        }

        // 웹훅에는 로그인한 요청자가 없다 — 소유권 대조의 상대는 주문의 고객 그 자신이다. 예전에는 고객 사본에서
        // 그 고객의 이메일을 거꾸로 찾아 넘기고, 받는 쪽이 다시 이메일로 id 를 찾았다(docs/48 §6-①).
        Long ownerId = order.getCustomerId();
        if (ownerId == null) {
            throw new PaymentVerificationException(
                    "주문자를 찾지 못했습니다: orderId=" + order.getOrderId());
        }

        log.info("Stripe 웹훅 수신: type={} orderId={}", type, order.getOrderId());
        // 사용자 토큰을 넘기지 않는다 — 결제 사가의 메시지는 토큰을 싣지 않는다(docs/54 §1.7).
        cahcedOrderService.recordPayment(trackingId, PaymentProvider.STRIPE, sessionId, ownerId);
    }

    /** 복귀 URL 에 붙이는 쿼리를 만들 때 쓴다. 지금은 단순하지만 값이 늘면 여기로 모은다. */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
