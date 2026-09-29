package com.project.order_service.payment.fx;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 실시간 환율의 출처.
 *
 * <p>{@link com.project.order_service.payment.PaymentCurrencyPolicy} 는 이 포트만 안다.
 * 어느 API 인지, 얼마나 캐시하는지는 구현의 일이다. 빈이 없으면(기본) 정책은 고정 환율만
 * 쓴다 — 켜는 것은 {@code payment.fx.live.enabled=true} 다.
 *
 * <h3>실패는 비어 있음이다</h3>
 *
 * <p>API 가 죽었거나, 통화가 없거나, 응답이 이상하면 <b>예외가 아니라 {@code empty}</b> 를
 * 돌려준다. 환율 API 장애가 주문 생성 장애가 되어서는 안 된다 — 정책이 고정 환율로
 * 넘어간다. 구현은 실패를 로그에 남기되 값을 지어내지 않는다.
 */
public interface FxRateSource {

    /**
     * {@code base} 1 단위 = {@code quote} 얼마. 둘 다 ISO 4217 대문자.
     *
     * @return 환율. 모르거나 실패하면 {@code empty}
     */
    Optional<BigDecimal> rate(String base, String quote);
}
