package com.project.order_service.payment;

import com.project.common.constants.PaymentProvider;

/**
 * 결제 제공자 <b>포트</b>.
 *
 * <h3>이 인터페이스가 생긴 이유</h3>
 *
 * <p>예전에는 결제 확인이 {@code OrderDataService.persisitPaypalPayment} 한 메서드 안에
 * 있었고, 그 안에 <b>두 종류의 것이 섞여 있었다</b>:
 *
 * <ul>
 *   <li><b>PayPal 에만 해당하는 것</b> — PayPal REST 로 주문을 조회하고 status 가
 *       {@code COMPLETED} 인지 본다</li>
 *   <li><b>모든 결제에 해당하는 것</b> — 확인된 금액을 <b>DB 의 주문 총액</b>과 대조하고,
 *       통화를 대조하고, 그 결제 참조가 이미 다른 주문에 쓰였는지 막고, 결제 아웃박스에
 *       적는다 (docs/35 의 네 가지 규칙)</li>
 * </ul>
 *
 * <p>둘이 섞여 있으면 <b>결제수단을 하나 추가할 때마다 아래 규칙이 복사된다.</b> 그리고
 * 복사본 중 하나에서 금액 대조를 빠뜨리면 그 수단으로만 결제 없이 주문이 완료된다 —
 * 원래 결함이 정확히 그런 모양이었다.
 *
 * <p>그래서 위쪽만 이 포트로 뽑았다. 아래쪽은 {@link PaymentRecordingService} 하나가
 * 갖고 있고, <b>새 제공자는 그 규칙을 자동으로 상속한다.</b> 결제수단을 추가하는 일은
 * 이제 이 인터페이스를 구현하고 {@link com.project.common.constants.PaymentProvider} 에
 * 값을 더하는 것이 전부다.
 */
public interface PaymentGateway {

    /** 이 게이트웨이가 담당하는 제공자. 레지스트리가 이 값으로 고른다. */
    PaymentProvider provider();

    /**
     * 브라우저가 보고한 결제 참조를 <b>제공자에게 직접 물어</b> 확인한다.
     *
     * @param providerPaymentId 신뢰하지 않는 값. PayPal 주문 id · Stripe 세션 id 등
     * @throws PaymentVerificationException 확인 자체가 불가능했을 때(네트워크·인증 등).
     *         <b>"결제되지 않았다"는 예외가 아니라</b> {@code completed=false} 로 돌려준다 —
     *         둘은 다른 사실이고, 호출자가 다른 문구를 보여 준다.
     */
    VerifiedPayment verify(String providerPaymentId);
}
