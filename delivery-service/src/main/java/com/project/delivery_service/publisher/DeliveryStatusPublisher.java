package com.project.delivery_service.publisher;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.outbox.OutboxRelayTrigger;
import com.project.delivery_service.domain.Delivery;
import com.project.delivery_service.domain.DeliveryStatus;
import com.project.delivery_service.outbox.DeliveryOutbox;

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
 * <p><b>아웃박스에 적는다(2026-11-21, docs/93 ③ 2단계).</b> 상태를 바꾼 그 트랜잭션 안에서 {@link DeliveryOutbox} 에 이벤트를 적고,
 * 커밋 직후 릴레이({@code DeliveryOutboxRelay})를 깨운다 — 상태와 이벤트가 함께 커밋되거나 함께 롤백된다. 보내기는 릴레이가 하고,
 * 실패하면 다음 주기에 다시 보낸다(발행 실패 {@value #FAILED_METRIC} · 발행 지연 {@code outbox_pending_*}).
 *
 * <p>🐛 그 전: ① 트랜잭션 안에서 바로 보내고 결과를 보지 않았다 — 롤백되면 저장되지 않은 상태가 나갔고 비동기 실패는 로그조차
 * 없었다. ② 그 뒤 '커밋 뒤에 보내고 실패를 센다' 로 고쳤지만 실패한 이벤트는 그대로 사라졌다 — 상태는 이미 DB 에 있어 다음
 * 폴링에서도 다시 감지되지 않았으므로 주문 상태(DISPATCHED · DELIVERED)와 알림이 멈췄다(경보만 울렸다). 이제 릴레이가 보낼 때까지
 * 행이 남는다.
 *
 * <p>[JA] アウトボックスに書く — 状態を変えたトランザクション内でイベントを書き、コミット直後にリレーを起こす(状態とイベントは一緒に
 * コミット · ロールバック)。以前はコミット後に直接送り失敗は数えるだけで、失敗したイベントは消え、注文の状態と通知が止まった。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryStatusPublisher {

    /** 상태 이벤트를 보내지 못한 수 — Prometheus {@code delivery_status_publish_failed_total}. */
    // [JA] 状態イベントを送れなかった数 — Prometheus {@code delivery_status_publish_failed_total}。
    public static final String FAILED_METRIC = "delivery.status.publish.failed";

    /**
     * 경보가 {@code increase()} 로 보는 계수기를 기동 때 0 으로 만든다(B-101) — 처음 늘 때 생기면 첫 값이 이미 1 이라 increase() 가
     * 첫 사고를 0 으로 본다(2026-10-22 실습: FAILED 3건에도 OrderOutboxRowFailed 가 울리지 않았다).
     * [JA] 警報が increase() で見るカウンターを起動時に 0 で作る — 初回の増加で生まれると最初の値が 1 で、increase() が初回を 0 と見る。
     */
    @jakarta.annotation.PostConstruct
    void registerAlertCounters() {
        io.micrometer.core.instrument.Metrics.counter(FAILED_METRIC);
    }

    private final DeliveryOutbox outbox;
    private final OutboxRelayTrigger deliveryOutboxTrigger;
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

    /**
     * 이벤트를 아웃박스에 적는다 — 부르는 쪽의 트랜잭션에 든다. 적지 못하면(DB) 예외가 그대로 올라가 상태 변경도 롤백된다 —
     * 상태만 바뀌고 이벤트가 없는 것이 이 아웃박스가 막으려던 바로 그 경우다.
     * [JA] イベントをアウトボックスに書く — 呼び出し側のトランザクションに入る。書けなければ例外がそのまま上がり状態変更もロールバック。
     */
    public void publish(Delivery delivery, DeliveryStatus status) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("deliveryId", delivery.getDeliveryId());
        event.put("orderId", delivery.getOrderId());
        event.put("customerId", delivery.getCustomerId());
        event.put("status", status.name());
        event.put("stage", status.stage().name());
        event.put("worthNotifying", status.isWorthNotifying());
        event.put("occurredAt", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).toString());
        // 알림 서비스가 이 값으로 중복을 거른다 — 같은 상태 전이가 재전달돼도 알림은 하나다.
        // [JA] 通知サービスがこの値で重複を除く — 同じ状態遷移が再配信されても通知は一つ。
        event.put("eventId", "delivery:" + delivery.getOrderId() + ":" + status.name());

        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("배송 상태 이벤트를 직렬화하지 못했다", e);
        }
        outbox.enqueue(topic, String.valueOf(delivery.getOrderId()), payload);
        deliveryOutboxTrigger.afterCommit();
        log.debug("배송 상태 이벤트를 아웃박스에 적음: orderId={} status={}", delivery.getOrderId(), status);
    }
}
