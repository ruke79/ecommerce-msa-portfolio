package com.project.order_service.payment.portone;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 포트원이 발급한 가상계좌 — 제공자 중립으로 읽은 것 (docs/49 §11).
 *
 * <p>무통장입금 상태 기계의 "입금 안내" 를 <b>PG 가 대신 만든 것</b>이다. 손님이 여기로
 * 입금하면 포트원이 결제 완료 웹훅을 보내고, 그것이 곧 입금 확인이다 — 관리자가 통장을
 * 볼 필요가 없다.
 *
 * @param bank          은행 이름·코드(V1 은 이름, V2 는 코드). 화면에 그대로 보여 준다
 * @param accountNumber 가상계좌 번호
 * @param holder        예금주(상점 쪽 표시 이름)
 * @param dueAt         입금 기한. 지나면 PG 가 계좌를 닫고 우리도 주문을 접는다. 없으면 null
 * @param amount        입금해야 할 금액 — 발급 요청 금액. 주문의 청구액과 대조한다
 * @param currency      대문자 ISO 코드
 */
public record VirtualAccount(
        String bank,
        String accountNumber,
        String holder,
        LocalDateTime dueAt,
        BigDecimal amount,
        String currency) {
}
