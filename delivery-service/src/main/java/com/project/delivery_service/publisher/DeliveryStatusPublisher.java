package com.project.delivery_service.publisher;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.delivery_service.domain.Delivery;
import com.project.delivery_service.domain.DeliveryStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 배송 상태 변경을 알린다.
 *
 * <p>두 곳이 듣는다:
 * <ul>
 *   <li><b>notification-service</b> — 알림·SMS. {@code OUT_FOR_DELIVERY} 와 {@code DELIVERED},
 *       그리고 통관 보류처럼 사용자가 조치해야 하는 상태가 특히 중요하다(docs/24).</li>
 *   <li><b>order-service</b> — 주문 상태를 {@code DISPATCHED}/{@code DELIVERED} 로 반영.
 *       그 값들은 enum 에만 있고 아무도 채우지 않던 것들이다(docs/25 §0).</li>
 * </ul>
 *
 * <p>발행 실패가 배송 처리를 막지 않는다. 상태는 이미 DB 에 있고, 다음 폴링에서 다시 전이가
 * 감지되지는 않으므로 알림 하나를 놓칠 수는 있다 — 그래도 배송 자체가 멈추는 것보다 낫다.
 * (아웃박스로 옮기는 것이 정석이지만, 그건 이 서비스에 outbox 테이블을 두는 별도 작업이다.)
 *
 * <p><b>커밋 뒤에 보낸다 · 실패를 센다.</b> 🐛 예전에는 트랜잭션 안에서 바로 보냈고 {@code send} 의 결과를 보지 않았다 —
 * 롤백되면 저장되지 않은 상태가 나갔고, 브로커 장애로 생긴 비동기 실패는 로그조차 없었다. 이제 트랜잭션이 있으면 커밋 뒤에 보내고,
 * 실패는 {@value #FAILED_METRIC} 로 센다(경보 {@code DeliveryStatusPublishFailed} — 그 주문은 상태가 멈췄을 수 있다).
 *
 * <p>[JA] コミットの後に送り、失敗を数える。以前はトランザクション内で即送信し結果を見ていなかった(ロールバックされた状態が出る ·
 * 非同期の失敗はログもない)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryStatusPublisher {

    /** 상태 이벤트를 보내지 못한 수 — Prometheus {@code delivery_status_publish_failed_total}. */
    public static final String FAILED_METRIC = "delivery.status.publish.failed";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${delivery.topics.status:delivery-status}")
    private String topic;

    /**
     * 상자 하나가 바뀐 뒤 — <b>주문 전체 상태</b>({@link Delivery#overallStatus()})가 바뀌었을 때만 그 값을 발행한다.
     *
     * <p>🐛 예전에는 바뀐 상자의 상태를 그대로 주문 단위 이벤트로 냈다. 분할 배송에서 상자 1 이 도착하면 {@code DELIVERED} 가 나가
     * order-service 가 주문을 DELIVERED 로 바꾸고, 자동 구매 확정 · 리뷰 자격까지 열렸다 — 상자 2 는 아직 이동 중인데. 또 eventId
     * ({@code delivery:<주문>:<상태>})에 상자 구분이 없어 상자 2 의 같은 상태 알림은 중복으로 걸러졌다. 이제 주문 단위 이벤트는 주문
     * 전체 상태로만 나가므로 eventId 도 그대로 맞는다.
     *
     * <p>[JA] 箱一つが変わった後 — 注文全体の状態が変わったときだけその値を発行する。以前は変わった箱の状態をそのまま注文単位の
     * イベントにしており、分割配送で箱 1 が届くと注文が DELIVERED になり自動購入確定まで進んだ(箱 2 はまだ移動中)。
     *
     * @param before 상자를 바꾸기 전의 {@code delivery.overallStatus()}
     * @return 발행했으면 true
     */
    public boolean publishIfOverallChanged(Delivery delivery, DeliveryStatus before) {
        DeliveryStatus after = delivery.overallStatus();
        if (after == before) {
            return false;
        }
        publish(delivery, after);
        return true;
    }

    public void publish(Delivery delivery, DeliveryStatus status) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("deliveryId", delivery.getDeliveryId());
            event.put("orderId", delivery.getOrderId());
            event.put("customerId", delivery.getCustomerId());
            event.put("status", status.name());
            event.put("stage", status.stage().name());
            event.put("worthNotifying", status.isWorthNotifying());
            event.put("occurredAt", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).toString());
            // 알림 서비스가 이 값으로 중복을 거른다 — 같은 상태 전이가 재전달돼도 알림은 하나다.
            event.put("eventId", "delivery:" + delivery.getOrderId() + ":" + status.name());

            String key = String.valueOf(delivery.getOrderId());
            String payload = objectMapper.writeValueAsString(event);
            Long orderId = delivery.getOrderId();
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                send(key, payload, orderId, status);
                            }
                        });
            } else {
                send(key, payload, orderId, status);
            }

        } catch (Exception e) {
            failed(delivery.getOrderId(), status, e);
        }
    }

    private void send(String key, String payload, Long orderId, DeliveryStatus status) {
        try {
            kafkaTemplate.send(topic, key, payload).whenComplete((result, ex) -> {
                if (ex != null) {
                    failed(orderId, status, ex);
                } else {
                    log.debug("배송 상태 발행: orderId={} status={}", orderId, status);
                }
            });
        } catch (RuntimeException e) {
            failed(orderId, status, e);
        }
    }

    private void failed(Long orderId, DeliveryStatus status, Throwable e) {
        io.micrometer.core.instrument.Metrics.counter(FAILED_METRIC).increment();
        log.error("배송 상태 발행 실패: orderId={} status={} — 주문 상태 · 알림이 멈췄을 수 있다: {}",
                orderId, status, e.toString());
    }
}
