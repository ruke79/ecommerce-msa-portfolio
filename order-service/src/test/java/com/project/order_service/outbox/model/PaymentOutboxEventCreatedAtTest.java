package com.project.order_service.outbox.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.project.common.outbox.OutboxStatus;

/**
 * 아웃박스 이벤트의 created_at 시간대 회귀 테스트 (docs/16 B-21).
 *
 * <p>이 컬럼은 단순 기록용이 아니라 <b>월 단위 RANGE 파티션의 파티션 키</b>다(docs/09).
 * 예전에는 {@code LocalDateTime.now(ZoneId.of("Asia/Seoul"))} 로 하드코딩돼 있어 다른 컬럼
 * (JPA 감사 = JVM 기본 시계)과 <b>9시간</b> 어긋난 값이 들어갔다. 그 상태로는 매월 말 9시간 동안
 * 생성된 이벤트가 <b>다음 달 파티션</b>에 저장되어, 보존 정책(DETACH/DROP)이 엉뚱한 행을 건드린다.
 *
 * <p>따라서 이 값은 반드시 JVM 기본 시계를 따라야 한다.
 */
class PaymentOutboxEventCreatedAtTest {

    @Test
    @DisplayName("createdAt 은 JVM 기본 시계를 따라야 한다 (하드코딩된 시간대 금지)")
    void createdAtFollowsSystemClock() {
        LocalDateTime before = LocalDateTime.now();
        PaymentOutboxEvent event = new PaymentOutboxEvent("agg-1", "Payment", "payment-response", "{}", OutboxStatus.STARTED);
        LocalDateTime after = LocalDateTime.now();

        assertThat(event.getCreatedAt())
                .as("아웃박스 created_at 이 다른 컬럼과 다른 시간대를 쓰면 파티션 라우팅이 어긋난다")
                .isBetween(before.minusSeconds(1), after.plusSeconds(1));
    }

    @Test
    @DisplayName("createdAt 이 시스템 시각에서 시간 단위로 벗어나지 않아야 한다")
    void createdAtIsNotHoursAway() {
        PaymentOutboxEvent event = new PaymentOutboxEvent("agg-1", "Payment", "payment-response", "{}", OutboxStatus.STARTED);
        long minutesOff = Math.abs(Duration.between(LocalDateTime.now(), event.getCreatedAt()).toMinutes());

        // Asia/Seoul 하드코딩이 살아 있으면 UTC 환경에서 540분(9시간) 차이가 난다.
        assertThat(minutesOff)
                .as("시스템 시각과 분 단위 이상 벌어지면 시간대가 하드코딩된 것이다")
                .isLessThan(5L);
    }
}
