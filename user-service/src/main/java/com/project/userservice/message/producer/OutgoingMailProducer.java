package com.project.userservice.message.producer;

import com.project.common.kafka.avro.AvroDtoSerializer;
import com.project.common.kafka.avro.OutgoingMailRequestAvroMapping;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.project.common.message.dto.request.OutgoingMailRequest;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * 메일 발송 요청을 {@code outgoing-mail} 토픽에 넣는다. 실제 발송은 notification-service 가 한다.
 *
 * <p><b>왜 여기서 SMTP 를 부르지 않게 바꿨나.</b> 예전에는 {@code RegistrationListener} 가
 * {@code JavaMailSender} 를 직접 호출했다. 그래서
 *
 * <ul>
 *   <li>메일 서버가 느리면 {@code mailExecutor} 스레드가 그만큼 붙잡혔다(응답은 이미 나간
 *       뒤였지만, 풀이 차면 뒤 요청의 메일이 밀린다)</li>
 *   <li>세 번 재시도해도 실패하면 <b>그 메일은 영영 사라졌다.</b> 남는 것은 ERROR 로그 한 줄뿐이고,
 *       사용자는 계정을 활성화할 방법이 없었다</li>
 *   <li>메일 문안·SMTP 설정·재시도 정책이 user-service 안에 있어, 다른 서비스가 메일을 보내려면
 *       그 코드를 복사해야 했다</li>
 * </ul>
 *
 * <p>이제 여기서는 Kafka 에 한 줄 넣고 끝난다. 내구성·재시도·DLQ 는 notification-service 의
 * {@code mail_message} 아웃박스가 맡는다(docs/29).
 *
 * <p><b>남은 창.</b> 이 발행 자체가 실패하면(브로커에 닿지 못하면) 메일은 여전히 사라진다.
 * 예전보다 창이 훨씬 좁을 뿐이다 — 같은 클러스터 안의 브로커에 쓰는 것과 외부 SMTP 공급자에
 * 왕복하는 것은 실패 확률이 다르다. 이 창을 완전히 닫으려면 user-service 의 아웃박스를 살려
 * 사용자 저장과 같은 트랜잭션에 메일 요청을 적어야 한다({@code OutboxScheduler} 가 구현체 없이
 * 껍데기만 남아 있다 — docs/29 §5).
 */
@Slf4j
@Component
public class OutgoingMailProducer {

    public static final String TOPIC = "outgoing-mail";

    private final DefaultKafkaProducerFactory<String, OutgoingMailRequest> factory;
    private final KafkaTemplate<String, OutgoingMailRequest> kafkaTemplate;

    /**
     * @param baseProducerConfig {@code KafkaProducerConfig#producerConfig()} 가 만든 설정.
     *                           SSL·부트스트랩 설정을 한 곳에서만 관리하려고 재사용한다.
     */
    public OutgoingMailProducer(@Qualifier("producerConfig") Map<String, Object> baseProducerConfig,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.observation.ObservationRegistry> observationRegistry) {
        Map<String, Object> config = new HashMap<>(baseProducerConfig);

        // ⚠️ 트랜잭션 ID 를 뺀다. 기존 프로듀서는 사가용이라 트랜잭셔널인데, 트랜잭셔널
        //    프로듀서로 트랜잭션 밖에서 send() 하면 "No transaction is in process" 로 죽는다.
        //    메일 요청은 사가의 일부가 아니므로 exactly-once 가 필요 없다 — 중복은 수신 측의
        //    source_event_id UNIQUE 가 막는다.
        config.remove(ProducerConfig.TRANSACTIONAL_ID_CONFIG);

        // 대신 멱등 프로듀서를 켠다. 브로커 재시도로 같은 레코드가 두 번 쓰이는 것을 막는다.
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.RETRIES_CONFIG, 3);
        // 여기서 오래 붙잡혀 있으면 가입 처리 스레드가 묶인다. 실패는 로그로 남기고 넘어간다.
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);

        // 값은 Avro 단일 객체 인코딩(docs/38 §1.16). 인스턴스로 넘기므로 맵의 VALUE_SERIALIZER 는 무시된다.
        this.factory = new DefaultKafkaProducerFactory<>(config,
                new org.apache.kafka.common.serialization.StringSerializer(),
                new AvroDtoSerializer<>(new OutgoingMailRequestAvroMapping()));
        this.kafkaTemplate = new KafkaTemplate<>(factory);
        // 빈이 아닌 템플릿이라 공통 후처리기가 닿지 않는다. 가입 요청의 추적이 메일 발송까지 이어지게
        // 레지스트리를 직접 넣는다(docs/51 §4d).
        observationRegistry.ifAvailable(registry -> {
            this.kafkaTemplate.setObservationRegistry(registry);
            this.kafkaTemplate.setObservationEnabled(true);
        });
    }

    /**
     * 메일 요청을 발행한다.
     *
     * <p><b>예외를 밖으로 던지지 않는다.</b> 이 메서드는 가입 완료 뒤에 불리는데, 여기서 예외가
     * 올라가면 이미 성공한 가입이 실패한 것처럼 보인다. 메일이 안 나간 것과 가입이 안 된 것은
     * 전혀 다른 문제다 — 전자는 재발송으로 복구되고 후자는 안 된다.
     *
     * @return 발행에 성공했는지
     */
    public boolean publish(OutgoingMailRequest request) {
        try {
            // 파티션 키를 수신 주소로 둔다. 같은 사람에게 가는 메일이 한 파티션에 모여
            // 순서가 유지된다 — "재설정 링크를 두 번 요청했는데 옛 것이 나중에 도착"을 막는다.
            kafkaTemplate.send(TOPIC, request.getToEmail(), request).get();
            log.info("메일 요청 발행: {}", request);   // toString 이 링크를 가린다
            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("메일 요청 발행이 중단되었습니다: {}", request);
            return false;

        } catch (Exception e) {
            // ⚠️ request 를 그대로 찍는다 — toString 이 params 를 키 이름만 남기고 가린다.
            log.error("메일 요청을 발행하지 못했습니다 — 이 메일은 나가지 않습니다: {} ({})",
                    request, e.getMessage());
            return false;
        }
    }

    /** 자체 프로듀서 팩토리를 만들었으므로 직접 닫는다. 안 닫으면 종료 시 스레드가 남는다. */
    @PreDestroy
    public void close() {
        factory.destroy();
    }

    /** 멱등 키를 만든다. 같은 사건에 대해 항상 같은 값이어야 한다. */
    public static String eventId(String prefix, Object discriminator) {
        return prefix + ":" + discriminator;
    }

    /** 사건을 특정할 값이 없을 때만 쓴다(재발송처럼 매번 새 메일이 나가야 하는 경우). */
    public static String randomEventId(String prefix) {
        return prefix + ":" + UUID.randomUUID();
    }
}
