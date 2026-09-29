package com.project.delivery_service.carrier;

import java.time.LocalDateTime;

import com.project.delivery_service.domain.DeliveryStatus;

/**
 * 어댑터가 돌려주는 <b>표준 이벤트</b>. 택배사 응답의 유일한 출력 형식이다.
 *
 * <p>{@code rawStatus} 와 {@code rawPayload} 를 함께 넘기는 것이 중요하다 — 매핑이 틀렸을 때
 * 원문이 없으면 왜 그 상태로 판정됐는지 재구성할 수 없고, 택배사가 문구를 바꿨을 때도
 * 알아챌 방법이 없다(docs/25 N-5).
 */
public record TrackingEvent(
        DeliveryStatus status,
        LocalDateTime occurredAt,
        String location,
        String description,
        String rawStatus,
        String rawPayload) {
}
