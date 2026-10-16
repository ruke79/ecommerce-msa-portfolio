package com.project.order_service.outbox.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.project.common.outbox.OutboxScheduler;
import com.project.common.outbox.OutboxStatus;
import com.project.order_service.outbox.PaymentOutboxHelper;
import com.project.order_service.outbox.model.PaymentOutboxEvent;
import com.project.order_service.ports.output.message.publisher.PaymentRequestKafkaPublisher;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 아웃박스 이벤트를 Kafka 로 내보내는 폴러.
 *
 * <p><b>레플리카가 둘 이상이어도 안전해야 한다.</b> 예전 구현은 그렇지 않았다:
 * <ol>
 *   <li>{@code findByOutboxStatus(STARTED)} 로 <b>잠금 없이</b> 조회 → 레플리카 A 와 B 가
 *       같은 행을 읽는다</li>
 *   <li>발행이 비동기라, 상태를 COMPLETED 로 바꾸는 콜백은 <b>이 트랜잭션이 커밋된 뒤</b>
 *       프로듀서 IO 스레드에서 실행된다 → 그 사이 행은 여전히 STARTED 다</li>
 * </ol>
 * 둘 중 하나만 있어도 중복 발행이 나고, 결제 요청이 두 번 나가는 것은 돈 문제다.
 *
 * <p>지금은 {@code FOR UPDATE SKIP LOCKED} 로 <b>선점</b>하고, 발행을 이 트랜잭션 안에서
 * <b>동기로</b> 기다린 뒤 상태를 확정한다. 잠금이 상태 확정까지 유지되므로 다른 레플리카가
 * 같은 행을 볼 수 없다.
 *
 * <p>그래도 Kafka 는 at-least-once 다 — 커밋 직전에 죽으면 재발행된다. 그래서 소비 측 멱등성
 * ({@code processed_event} 원장)이 여전히 필요하다. 이 변경이 없애는 것은 <b>레플리카 때문에
 * 생기는</b> 중복이지, 전달 보장에서 오는 중복이 아니다.
 *
 * <p>[JA] アウトボックスイベントを Kafka に送出するポーラー。
 *
 * <p><b>レプリカが 2 つ以上あっても安全でなければならない。</b>以前の実装はそうではなかった:
 * <ol>
 *   <li>{@code findByOutboxStatus(STARTED)} で<b>ロックなしに</b>取得 → レプリカ A と B が
 *       同じ行を読む</li>
 *   <li>発行が非同期なので、状態を COMPLETED に変えるコールバックは<b>このトランザクションがコミットされた後</b>に
 *       プロデューサの IO スレッドで実行される → その間、行は依然として STARTED である</li>
 * </ol>
 * どちらか一方だけでも重複発行が起き、決済リクエストが二度出ていくのはお金の問題である。
 *
 * <p>今は {@code FOR UPDATE SKIP LOCKED} で<b>先取り</b>し、発行をこのトランザクション内で
 * <b>同期的に</b>待ってから状態を確定する。ロックが状態確定まで保持されるので、他のレプリカが
 * 同じ行を見ることはできない。
 *
 * <p>それでも Kafka は at-least-once である — コミット直前に落ちれば再発行される。だから消費側の冪等性
 * ({@code processed_event} 台帳)は依然として必要である。この変更がなくすのは<b>レプリカが原因で
 * 生じる</b>重複であって、配信保証に由来する重複ではない。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentOutboxScheduler implements OutboxScheduler {

    private final PaymentOutboxHelper paymentOutboxHelper;
    private final PaymentRequestKafkaPublisher paymentRequestMessagePublisher;
    /** 주문 상태 변경(docs/76) — 토픽 order-status. */
    private final com.project.order_service.ports.output.message.publisher.OrderStatusKafkaPublisher orderStatusPublisher;
    private final com.project.common.outbox.OutboxTraceContext outboxTraceContext;
    /** 결제 확정 차감 · 반품 복원 — 토픽 product-update. */
    private final com.project.order_service.ports.output.message.publisher.ProductUpdateKafkaPublisher productUpdatePublisher;
    /** 결제 뒤 장바구니 비우기 — 토픽 cart-empty. */
    private final com.project.order_service.ports.output.message.publisher.CartEmptyKafkaPublisher cartEmptyPublisher;

    // 한 번에 선점할 행 수. 제한이 없으면 한 레플리카가 밀린 이벤트를 전부 잠가
    // 나머지 레플리카가 놀게 되고, 트랜잭션도 길어진다.
    // [JA] 一度に先取りする行数。制限がないと一つのレプリカが溜まったイベントをすべてロックしてしまい、
    //      残りのレプリカが遊ぶことになり、トランザクションも長くなる。
    @Value("${order-service.outbox-batch-size:100}")
    private int batchSize;

    @Override
    @Transactional
    @Scheduled(fixedDelayString = "${order-service.outbox-scheduler-fixed-rate}",
                initialDelayString = "${order-service.outbox-scheduler-initial-delay}")
    public void processOutboxMessage() {

        List<PaymentOutboxEvent> outboxList =
                paymentOutboxHelper.claimPaymentOutboxEvents(OutboxStatus.STARTED, PageRequest.of(0, batchSize));

        if (outboxList.isEmpty()) {
            return;
        }

        log.info("Claimed {} PaymentOutboxEvent with ids: {}, sending to message bus!",
                outboxList.size(),
                outboxList.stream().map(outboxMessage ->
                        outboxMessage.getId().toString()).collect(Collectors.joining(",")));

        for (PaymentOutboxEvent outbox : outboxList) {
            boolean settled;
            try {
                settled = relay(outbox);
            } catch (RuntimeException e) {
                if (com.project.common.kafka.DltRetryPolicy.isTransient(e)) {
                    throw e;   // DB 가 흔들린다 — 배치째 롤백하고 다음 폴링에서 다시(행의 잘못이 아니다)
                }
                // 🔒 행 하나의 영구 오류(깨진 페이로드 등)는 그 행만 FAILED 로 — 🐛 예전에는 예외가 배치 트랜잭션째 롤백시켜 같은 행을
                //    매 폴링 다시 집었다. 그 뒤의 결제 요청 · 주문 상태 · 재고 차감이 전부 멈췄다. 일시 실패는 발행기가 false 로 알린다.
                // [JA] 一行の恒久エラーはその行だけ FAILED に — 以前は例外がバッチごとロールバックし、後ろの行がすべて止まった。
                log.error("아웃박스 행을 보낼 수 없다 — FAILED 로 둔다(수동 확인). id={} type={} ({})",
                        outbox.getId(), outbox.getEventType(), e.toString());
                io.micrometer.core.instrument.Metrics.counter(FAILED_METRIC, "type", String.valueOf(outbox.getEventType())).increment();
                updateOutboxStatus(outbox, OutboxStatus.FAILED);
                continue;
            }

            if (!settled) {
                // 브로커가 흔들리는 중이다. 남은 이벤트를 계속 시도하면 배치 크기만큼 타임아웃을
                // 곱해 기다리게 되고, 그동안 행 잠금과 DB 커넥션을 쥐고 있게 된다.
                // 여기서 멈추면 잠기지 않은 나머지는 다음 폴링(또는 다른 레플리카)이 가져간다.
                // [JA] ブローカーが不安定になっている。残りのイベントを試し続けると、バッチサイズ分だけタイムアウトを
                //      掛け合わせて待つことになり、その間ずっと行ロックと DB コネクションを握り続ける。
                //      ここで止めれば、ロックされていない残りは次のポーリング(または別のレプリカ)が持っていく。
                log.warn("Aborting outbox batch after a transient publish failure; {} event(s) left for the next poll",
                        outboxList.size() - outboxList.indexOf(outbox) - 1);
                break;
            }
        }
    }

    /** 보낼 수 없어 FAILED 로 둔 행 수 — Prometheus {@code order_outbox_failed_total{type}}, 경보 OrderOutboxRowFailed. */
    public static final String FAILED_METRIC = "order.outbox.failed";

    /** @return 상태가 확정됐으면 true, 일시 실패라 다음 폴링에 다시 보내야 하면 false. 영구 오류는 예외 */
    private boolean relay(PaymentOutboxEvent outbox) {
        boolean settled;
        if ("payment-request".equals(outbox.getEventType())) {
            // 행을 쓴 요청의 추적 문맥 아래에서 보낸다 — 결제 요청이 원래 주문 요청의 추적에 이어진다(docs/51 §4e).
            // [JA] 行を書いたリクエストのトレース文脈の下で送る — 決済リクエストが元の注文リクエストのトレースにつながる(docs/51 §4e)。
            settled = outboxTraceContext.resume(outbox.getTraceContext(), "outbox relay payment-request",
                    () -> paymentRequestMessagePublisher.publish(outbox, this::updateOutboxStatus));
        } else if (PaymentOutboxHelper.ORDER_STATUS_EVENT.equals(outbox.getEventType())) {
            // 상태를 바꾼 요청의 추적 아래에서 — 주문 진행 조회가 그 요청의 추적으로 이어진다(docs/76).
            // [JA] 状態を変えたリクエストのトレースの下で — 注文進行の照会がそのリクエストのトレースにつながる(docs/76)。
            settled = outboxTraceContext.resume(outbox.getTraceContext(), "outbox relay order-status",
                    () -> orderStatusPublisher.publish(outbox, this::updateOutboxStatus));
        } else if (PaymentOutboxHelper.PRODUCT_UPDATE_EVENT.equals(outbox.getEventType())) {
            settled = outboxTraceContext.resume(outbox.getTraceContext(), "outbox relay product-update",
                    () -> productUpdatePublisher.publish(outbox, this::updateOutboxStatus));
        } else if (PaymentOutboxHelper.CART_EMPTY_EVENT.equals(outbox.getEventType())) {
            settled = outboxTraceContext.resume(outbox.getTraceContext(), "outbox relay cart-empty",
                    () -> cartEmptyPublisher.publish(outbox, this::updateOutboxStatus));
        } else {
            // 알 수 없는 이벤트 타입을 STARTED 로 두면 매 폴링마다 다시 집어 무한 반복한다.
            // [JA] 未知のイベントタイプを STARTED のままにすると、ポーリングのたびに拾い直して無限に繰り返す。
            throw new IllegalStateException("Unknown outbox event type '" + outbox.getEventType() + "'");
        }
        return settled;
    }

    private void updateOutboxStatus(PaymentOutboxEvent outboxEvent, OutboxStatus outboxStatus) {
        outboxEvent.setOutboxStatus(outboxStatus);
        paymentOutboxHelper.save(outboxEvent);
        log.info("PaymentOutboxEvent is updated with outbox status: {}", outboxStatus.name());
    }

}
