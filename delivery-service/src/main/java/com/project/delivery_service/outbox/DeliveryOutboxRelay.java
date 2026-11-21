package com.project.delivery_service.outbox;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.project.delivery_service.publisher.DeliveryStatusPublisher;

import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;

/**
 * 배송 아웃박스 릴레이 — STARTED 행을 오래된 순으로 Kafka 에 보내고 COMPLETED 로 표시한다(docs/93 ③ 2단계).
 *
 * <ul>
 *   <li><b>한 파드만</b> — 트랜잭션 advisory 잠금. 다른 파드가 돌고 있으면 이번 주기는 건너뛴다(같은 주문의 이벤트 순서).</li>
 *   <li><b>브로커 오류면 그 묶음을 멈춘다</b> — 첫 실패 뒤의 행은 다음 주기로(순서 · 브로커가 죽었을 때 행마다 기다리지 않는다:
 *       {@code max.block.ms} 5초 + 응답 상한 한 번). 그 행은 STARTED 그대로 — 발행 지연 경보({@code OutboxPublishLagging})가 본다.</li>
 *   <li><b>다시 보내도 소용없는 오류</b>(너무 큼 · 직렬화 · 토픽 이름)면 그 행만 FAILED — 뒤 행은 계속 간다.</li>
 *   <li>실패는 {@code delivery_status_publish_failed_total} 로 센다(경보 {@code DeliveryStatusPublishFailed}).</li>
 *   <li>받는 쪽(order · notification)은 eventId({@code delivery:<주문>:<상태>})로 중복을 거른다 — 보냈는데 표시 전에 죽어 한 번 더 가도 같다.</li>
 * </ul>
 *
 * <p>[JA] 配送アウトボックスのリレー — STARTED の行を古い順に Kafka へ送り COMPLETED にする。一つの Pod だけ(advisory ロック)、ブローカーの
 *      エラーならそのまとまりを止めて次の周期へ(順序)、送り直しても無駄なエラーならその行だけ FAILED。受け手は eventId で重複を除く。
 */
@Slf4j
@Component
public class DeliveryOutboxRelay {

    /** 한 번에 보내는 행 수 — 상태 변경은 분당 몇 건이라 넉넉하다. */
    // [JA] 一度に送る行数。
    static final int BATCH = 100;

    private final DeliveryOutbox outbox;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate tx;
    private final long sendTimeoutMs;

    public DeliveryOutboxRelay(DeliveryOutbox outbox, KafkaTemplate<String, String> kafkaTemplate,
            PlatformTransactionManager transactionManager,
            @Value("${delivery.outbox.send-timeout-ms:10000}") long sendTimeoutMs) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
        this.tx = new TransactionTemplate(transactionManager);
        this.sendTimeoutMs = sendTimeoutMs;
    }

    /** 한 번 돈 결과. */
    // [JA] 一回の結果。
    public record Result(int sent, int failed, int deferred, boolean skipped) {
    }

    @Scheduled(fixedDelayString = "${delivery.outbox.fixed-delay-ms:10000}",
            initialDelayString = "${delivery.outbox.initial-delay-ms:5000}")
    public void scheduled() {
        try {
            relayOnce();
        } catch (RuntimeException e) {
            log.warn("배송 아웃박스 릴레이가 이번 주기에 실패했다 — 다음 주기에 다시 한다: {}", e.toString());
        }
    }

    public Result relayOnce() {
        Result r = tx.execute(status -> {
            if (!outbox.tryRelayLock()) {
                return new Result(0, 0, 0, true);
            }
            List<DeliveryOutbox.Row> rows = outbox.pending(BATCH);
            int sent = 0;
            int failed = 0;
            int deferred = 0;
            boolean stop = false;
            for (DeliveryOutbox.Row row : rows) {
                if (stop) {
                    deferred++;
                    continue;
                }
                try {
                    kafkaTemplate.send(row.topic(), row.key(), row.payload()).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                    outbox.markCompleted(row);
                    sent++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    stop = true;
                    deferred++;
                } catch (Exception e) {
                    Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                    Metrics.counter(DeliveryStatusPublisher.FAILED_METRIC).increment();
                    if (isPermanent(cause)) {
                        outbox.markFailed(row);
                        failed++;
                        log.error("배송 상태 발행 영구 실패 — 그 행만 FAILED: key={} id={} ({})", row.key(), row.id(),
                                cause.getClass().getSimpleName());
                    } else {
                        // 브로커가 없거나 느리다 — 이 행과 뒤 행은 다음 주기에(순서). [JA] 次の周期へ(順序)。
                        outbox.markRetry(row);
                        stop = true;
                        deferred++;
                        log.warn("배송 상태 발행 실패 — 다음 주기에 다시 보낸다: key={} attempts={} ({})", row.key(),
                                row.attempts() + 1, cause.getClass().getSimpleName());
                    }
                }
            }
            return new Result(sent, failed, deferred, false);
        });
        if (r != null && (r.sent() + r.failed() + r.deferred()) > 0) {
            log.info("배송 아웃박스 처리: 발행={} 실패={} 미룸={}", r.sent(), r.failed(), r.deferred());
        }
        return r;
    }

    /** 다시 보내도 같은 결과인 오류 — 그 행을 FAILED 로. [JA] 送り直しても同じ結果のエラー。 */
    static boolean isPermanent(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof RecordTooLargeException || c instanceof SerializationException || c instanceof InvalidTopicException) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }
}
