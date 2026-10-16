package com.project.pay_service.outbox.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.project.common.outbox.OutboxScheduler;
import com.project.common.outbox.OutboxStatus;
import com.project.pay_service.outbox.OrderOutboxHelper;
import com.project.pay_service.outbox.model.OrderOutboxEvent;
import com.project.pay_service.ports.output.message.publisher.PaymentResponseKafkaPublisher;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 결제 응답 아웃박스 폴러.
 *
 * <p>order-service 의 {@code PaymentOutboxScheduler} 와 같은 이유로 고쳤다: 잠금 없는 조회 +
 * 비동기 상태 갱신은 레플리카가 둘 이상일 때 <b>같은 결제 응답을 두 번 발행</b>한다. 주문
 * 서비스는 같은 사가 단계를 두 번 받게 된다. {@code FOR UPDATE SKIP LOCKED} 로 선점하고
 * 발행을 트랜잭션 안에서 동기로 확정한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderOutboxScheduler implements OutboxScheduler {


    private final OrderOutboxHelper orderOutboxHelper;
    private final PaymentResponseKafkaPublisher paymentResponseMessagePublisher;
    private final com.project.common.outbox.OutboxTraceContext outboxTraceContext;

    @Value("${pay-service.outbox-batch-size:100}")
    private int batchSize;


    @Override
    @Transactional
    @Scheduled(fixedDelayString = "${pay-service.outbox-scheduler-fixed-rate}",
                initialDelayString = "${pay-service.outbox-scheduler-initial-delay}")
    public void processOutboxMessage() {

        List<OrderOutboxEvent> outboxList =
                orderOutboxHelper.claimOrderOutboxEvents(OutboxStatus.STARTED, PageRequest.of(0, batchSize));

        if (outboxList.isEmpty()) {
            return;
        }

        log.info("Claimed {} OrderOutboxEvent with ids: {}, sending to message bus!",
                outboxList.size(),
                outboxList.stream().map(outboxMessage ->
                        outboxMessage.getId().toString()).collect(Collectors.joining(",")));

        for (OrderOutboxEvent outbox : outboxList) {
            // 결제 요청을 받은 리스너의 추적 문맥 아래에서 보낸다 — 결제 응답이 같은 사가 추적에 이어진다(docs/51 §4e).
            boolean settled;
            try {
                settled = outboxTraceContext.resume(outbox.getTraceContext(), "outbox relay payment-response",
                        () -> paymentResponseMessagePublisher.publish(outbox, this::updateOutboxStatus));
            } catch (RuntimeException e) {
                if (com.project.common.kafka.DltRetryPolicy.isTransient(e)) {
                    throw e;   // DB 가 흔들린다 — 배치째 롤백하고 다음 폴링에서 다시(행의 잘못이 아니다)
                }
                // 🔒 행 하나의 영구 오류(깨진 페이로드 등)는 그 행만 FAILED 로(order 와 같다) — 🐛 예전에는 예외가 배치 트랜잭션째 롤백시켜
                //    같은 행을 매 폴링 맨 앞에서 다시 집었다. 그 뒤의 결제 응답이 전부 멈췄다(PY6).
                // [JA] 一行の恒久エラーはその行だけ FAILED に — 以前はバッチごとロールバックし、後ろの決済応答がすべて止まった。
                log.error("결제 응답 아웃박스 행을 보낼 수 없다 — FAILED 로 둔다(수동 확인). id={} ({})", outbox.getId(), e.toString());
                io.micrometer.core.instrument.Metrics.counter(FAILED_METRIC).increment();
                updateOutboxStatus(outbox, OutboxStatus.FAILED);
                continue;
            }
            if (!settled) {
                // 브로커가 흔들리는 중이다. 남은 배치를 계속 시도하면 타임아웃 × 배치 크기만큼
                // 행 잠금과 커넥션을 쥐고 있게 된다. 나머지는 다음 폴링이 가져간다.
                log.warn("Aborting outbox batch after a transient publish failure; {} event(s) left for the next poll",
                        outboxList.size() - outboxList.indexOf(outbox) - 1);
                break;
            }
        }
    }

    /** 보낼 수 없어 FAILED 로 둔 결제 응답 행 수 — Prometheus {@code pay_outbox_failed_total}, 경보 PayOutboxRowFailed. */
    public static final String FAILED_METRIC = "pay.outbox.failed";

    private void updateOutboxStatus(OrderOutboxEvent outboxEvent, OutboxStatus outboxStatus) {
        outboxEvent.setOutboxStatus(outboxStatus);
        orderOutboxHelper.save(outboxEvent);
        log.info("OutboxEvent is updated with outbox status: {}", outboxStatus.name());
    }

}