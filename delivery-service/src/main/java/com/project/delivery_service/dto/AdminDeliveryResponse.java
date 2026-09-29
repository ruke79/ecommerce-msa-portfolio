package com.project.delivery_service.dto;

import java.time.LocalDateTime;
import java.util.Map;

import com.project.delivery_service.domain.Carrier;
import com.project.delivery_service.domain.Delivery;

/**
 * 관리자 목록의 한 줄 — 고객 응답에 없는 {@code customerId}·생성 시각을 더한 것.
 *
 * <p>고객 응답({@link DeliveryResponse})은 "누구의" 를 싣지 않는다 — 요청한 사람 것이니까.
 * 관리자는 여러 사람의 배송을 한 화면에 보므로 그 둘이 필요하다.
 */
public record AdminDeliveryResponse(
        /** 주문 번호와 같은 이유로 문자열이다({@link DeliveryResponse#orderId()}). */
        String customerId,
        LocalDateTime createdAt,
        DeliveryResponse delivery) {

    public static AdminDeliveryResponse from(Delivery delivery, Map<String, Carrier> carriers) {
        return new AdminDeliveryResponse(String.valueOf(delivery.getCustomerId()), delivery.getCreatedAt(),
                DeliveryResponse.from(delivery, carriers));
    }
}
