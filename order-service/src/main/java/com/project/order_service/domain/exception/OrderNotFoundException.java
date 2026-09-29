package com.project.order_service.domain.exception;

import com.project.common.domain.exception.DomainException;

/**
 * 주문이 없거나 <b>요청자의 것이 아니다.</b>
 *
 * <p>구매 확정(docs/25 §12)에서는 둘을 구분하지 않는다. 남의 주문에 403 을 주면 "그 번호의
 * 주문이 존재한다" 는 사실을 알려 줘 번호를 훑는 것 자체를 막지 못한다
 * ({@code OrderDataService.getOrder} 와 같은 이유). 컨트롤러는 이것을 404 로 바꾼다.
 */
public class OrderNotFoundException extends DomainException {

    public OrderNotFoundException(String message) {
        super(message);
    }

    public OrderNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
