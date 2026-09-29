package com.project.saga_projection.config;

import java.util.Map;

import org.apache.avro.message.MissingSchemaException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.errors.DeserializationExceptionHandler;
import org.apache.kafka.streams.processor.ProcessorContext;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;

/**
 * 입력 레코드를 못 읽었을 때 — <b>실패의 종류에 따라</b> 멈추거나 건너뛴다 (docs/55 §8).
 *
 * <h3>🐛 예전: 레코드 하나에 프로젝션 전체가 멈췄다</h3>
 *
 * <p>처리기를 두지 않으면 Kafka Streams 의 기본값(LogAndFail)이다. 깨진 레코드 하나를 만나면 처리 스레드가 죽고
 * ({@code SHUTDOWN_CLIENT}) 재시작해도 같은 오프셋에서 또 죽는다 — 그 뒤의 모든 주문이 사가 조회에서 멈춘다.
 * 반대로 무조건 건너뛰면(LogAndContinue) 더 나쁜 경우가 있다.
 *
 * <h3>그래서 둘로 나눈다</h3>
 *
 * <ul>
 *   <li><b>모르는 작성 스키마</b>({@link MissingSchemaException}) — 이 파드보다 <b>새</b> 프로듀서가 쓴 레코드다. 롤링
 *       배포 중이라면 곧 새 파드가 이어받아 그 레코드를 읽을 수 있다. 여기서 건너뛰면 오프셋이 커밋되어(EOS) 새 파드도
 *       다시 읽지 않는다 — <b>프로젝션이 그 기록을 영영 잃는다.</b> 그래서 <b>멈춘다</b>: 스레드가 죽고 readiness 가
 *       내려가고, 새 파드가 마지막 커밋부터 다시 읽는다. 배포 중이 아닌데 이것이 나면 프로듀서가 아무 소비자도 모르는
 *       스키마로 쓴 것이다(스키마 진화 규칙 위반, docs/55 §4·§5) — 멈춘 채로 경보가 울린다.</li>
 *   <li><b>그 밖의 역직렬화 실패</b>(깨진 바이트, 잘못된 JSON, 아는 스키마인데 본문이 어긋남) — 몇 번을 다시 읽어도
 *       같다(poison). 멈추면 뒤의 모든 주문이 인질이 된다. <b>건너뛰고</b> 좌표(토픽·파티션·오프셋)를 남기고 계수한다.
 *       원본은 입력 토픽에 그대로 있으므로(보존 기간 동안) 좌표로 찾아 볼 수 있고, 프로젝션은 입력 토픽에서 다시 접어
 *       재구성할 수 있다(애플리케이션 리셋).</li>
 * </ul>
 *
 * <p>값은 로그에 남기지 않는다 — 주문·결제 요청이다. 좌표와 예외 종류만.
 *
 * <p>계수기 {@code saga_projection_deserialization_failures_total{topic, action}} — action 은 {@code skipped}·
 * {@code halted}. 경보는 {@code observability/alert-rules.yml} 의 saga-projection 그룹. Kafka 가 클래스 이름으로
 * 만들므로 Spring 빈이 아니다 — 전역 레지스트리(Boot 가 자기 레지스트리를 여기에 붙인다)에 기록한다.
 */
@Slf4j
public class SagaDeserializationExceptionHandler implements DeserializationExceptionHandler {

    static final String METRIC = "saga.projection.deserialization.failures";

    @Override
    public DeserializationHandlerResponse handle(ProcessorContext context, ConsumerRecord<byte[], byte[]> record,
            Exception exception) {
        if (isUnknownWriterSchema(exception)) {
            count(record.topic(), "halted");
            log.error("saga-projection: 모르는 작성 스키마의 레코드 — 건너뛰면 프로젝션이 그 기록을 영영 잃으므로 멈춘다. "
                    + "롤링 배포 중이면 새 파드가 이어받는다. 배포 중이 아니면 프로듀서가 아무 소비자도 모르는 스키마로 쓴 것이다"
                    + "(docs/55 §4·§5). topic={} partition={} offset={}",
                    record.topic(), record.partition(), record.offset());
            return DeserializationHandlerResponse.FAIL;
        }
        count(record.topic(), "skipped");
        log.error("saga-projection: 읽을 수 없는 레코드를 건너뛴다 — 다시 읽어도 같다. 원본은 입력 토픽에 있다. "
                + "topic={} partition={} offset={} error={}",
                record.topic(), record.partition(), record.offset(), describe(exception));
        return DeserializationHandlerResponse.CONTINUE;
    }

    @Override
    public void configure(Map<String, ?> configs) {
    }

    static boolean isUnknownWriterSchema(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof MissingSchemaException) {
                return true;
            }
        }
        return false;
    }

    /** 예외 종류와 원인의 종류만 — 메시지에 레코드 값이 섞일 수 있어 싣지 않는다. */
    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root == e ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + " ← " + root.getClass().getSimpleName();
    }

    private static void count(String topic, String action) {
        Counter.builder(METRIC)
                .description("saga-projection 이 읽지 못한 입력 레코드 — skipped(건너뜀) · halted(멈춤)")
                .tag("topic", topic)
                .tag("action", action)
                .register(Metrics.globalRegistry)
                .increment();
    }
}
