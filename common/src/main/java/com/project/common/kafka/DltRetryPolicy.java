package com.project.common.kafka;

import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.BackOff;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 컨슈머 실패의 재시도 · DLT 정책 — 서비스마다의 {@code KafkaDltConfig} 가 쓴다(catalog 에서 시작, order 의 결제 응답도).
 *
 * <ul>
 *   <li><b>일시적인 실패</b>(교착 40P01 · 직렬화 실패 · 잠금 대기 · 커넥션 풀 고갈 · DB 페일오버)는 약 3분 다시 한다 —
 *       🐛 예전에는 모든 실패가 1 · 2 · 4초 세 번 뒤 DLT 였다(결제 확정 차감 · 결제 응답이 7초 만에 사라졌다)</li>
 *   <li>DLT 로 가는 순간을 센다({@value #DLT_METRIC}{topic}) — 경보 KafkaDeadLetterPublished. 예전에는 격리가 조용했다</li>
 * </ul>
 *
 * <p>[JA] コンシューマ失敗の再試行 · DLT 方針 — 一時的な失敗は約 3 分再試行し、DLT へ送った瞬間を数える。
 */
public final class DltRetryPolicy {

    /** DLT 로 보낸 레코드 수 — Prometheus {@code kafka_dlt_records_total{topic}}. */
    public static final String DLT_METRIC = "kafka.dlt.records";

    /**
     * 경보가 {@code increase()} 로 보는 계수기를 기동이 끝날 때 리스너 토픽마다(DltAlertCountersAutoConfiguration) 0 으로 만든다(B-101) — 처음 늘 때 생기면 첫 값이 이미 1 이라 increase() 가
     * 첫 사고를 0 으로 본다(2026-10-22 실습: FAILED 3건에도 OrderOutboxRowFailed 가 울리지 않았다).
     * [JA] 警報が increase() で見るカウンターを起動時に 0 で作る — 初回の増加で生まれると最初の値が 1 で、increase() が初回を 0 と見る。
     */
    public static void registerAlertCounters(MeterRegistry meters, java.util.Collection<String> topics) {
        for (String topic : topics) {
            meters.counter(DLT_METRIC, "topic", topic);
        }
    }

    private DltRetryPolicy() {
    }

    /** DLT 로 보내기 전에 센다. */
    public static ConsumerRecordRecoverer counting(ConsumerRecordRecoverer recoverer, MeterRegistry meters) {
        return (record, ex) -> {
            meters.counter(DLT_METRIC, "topic", record.topic()).increment();
            recoverer.accept(record, ex);
        };
    }

    /** 일시적인 실패의 백오프 — 1 · 2 · 4 · 8 · 16 · 30 · 30 · 30 · 30 · 30초(약 3분). 한 번 쉬는 시간은 max.poll.interval(5분)보다 짧다. */
    public static BackOff transientBackOff() {
        ExponentialBackOffWithMaxRetries b = new ExponentialBackOffWithMaxRetries(10);
        b.setInitialInterval(1_000L);
        b.setMultiplier(2.0);
        b.setMaxInterval(30_000L);
        return b;
    }

    /** 원인 사슬에 일시적인 DB 실패가 있는가 — 시간이 지나면 같은 메시지가 성공할 수 있는 것. */
    public static boolean isTransient(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof org.springframework.dao.TransientDataAccessException
                    || t instanceof org.springframework.dao.PessimisticLockingFailureException
                    || t instanceof org.springframework.dao.OptimisticLockingFailureException
                    || t instanceof org.springframework.transaction.CannotCreateTransactionException
                    || t instanceof java.sql.SQLTransientException
                    || t instanceof java.sql.SQLRecoverableException) {
                return true;
            }
            if (t instanceof java.sql.SQLException sql && sql.getSQLState() != null
                    && (sql.getSQLState().startsWith("40") || sql.getSQLState().startsWith("08") || "55P03".equals(sql.getSQLState()))) {
                return true;   // 40001 직렬화 실패 · 40P01 교착 · 08xxx 연결 · 55P03 잠금 대기 시간 초과
            }
        }
        return false;
    }
}
