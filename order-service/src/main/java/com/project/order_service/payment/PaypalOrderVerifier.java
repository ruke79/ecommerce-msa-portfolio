package com.project.order_service.payment;

/**
 * PayPal 이 <b>실제로</b> 그 주문을 결제 완료 처리했는지 확인한다.
 *
 * <p>왜 필요한가. 이 서비스의 결제 반영 경로는 브라우저가 보낸 값을 그대로 믿고 있었다:
 *
 * <pre>
 *   POST /api/order/payment/paypal
 *   { "trackingId": "...", "paypalOrderId": "PP-1", "amounts": 59.98,
 *     "paymentStatus": "COMPLETED", "orderStatus": "NOT_PROCESSED" }
 * </pre>
 *
 * 서버는 이 중 무엇도 검증하지 않고 아웃박스에 넣었다. 즉 <b>결제하지 않고도 결제 완료를
 * 만들 수 있었다</b> — 위 요청을 curl 로 보내면 끝이다. 금액도 클라이언트가 정했다.
 *
 * <p>PayPal JS SDK 가 브라우저에서 캡처를 마쳤다는 사실은 <b>서버 입장에서 증거가 아니다.</b>
 * 브라우저는 신뢰 경계 밖이고, SDK 가 돌려준 {@code details.id} 는 그저 문자열이다.
 * 유일하게 믿을 수 있는 것은 <b>서버가 PayPal 에 직접 물어본 답</b>이다.
 *
 * <p>구현은 둘이다:
 * <ul>
 *   <li>{@link HttpPaypalOrderVerifier} — PayPal Orders API 에 실제로 조회한다(운영 기본값)</li>
 *   <li>{@link DisabledPaypalOrderVerifier} — 검증을 건너뛴다. 자격증명 없이 도는 로컬 개발용이고,
 *       기동할 때마다 경고를 찍는다. <b>운영에서 켜면 안 된다.</b></li>
 * </ul>
 */
public interface PaypalOrderVerifier {

    /**
     * @param paypalOrderId 브라우저가 보고한 PayPal 주문 ID (신뢰하지 않는 값)
     * @return PayPal 이 확인해 준 결제 내역
     * @throws PaypalVerificationException 결제가 확인되지 않았을 때. <b>이 경우 주문을
     *         결제 완료로 처리해서는 안 된다.</b>
     */
    VerifiedPaypalOrder verify(String paypalOrderId);

    /**
     * 캡처된 결제를 환불한다 — 금액을 명시하므로 전액이든 일부든 같은 호출이다.
     *
     * <p>PayPal 은 <b>주문</b> id 가 아니라 <b>캡처</b> id 로 환불한다. 구현이 주문을 조회해
     * 캡처 id 를 찾아간다 — 호출자는 저장해 둔 주문 id 만 안다.
     *
     * @param refundKey 이 환불의 멱등 키({@code PayPal-Request-Id} 에 실린다). 부분 환불은
     *                  같은 캡처에 환불이 여럿이라 캡처 id 만으로는 두 번째가 첫 번째의
     *                  응답으로 대체된다. {@code null} 이면 캡처 id 만
     */
    RefundedPaypalOrder refund(String paypalOrderId, java.math.BigDecimal amount, String currency,
            String refundKey);
}
