package com.project.userservice.message.producer;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 프로필 변경·탈퇴를 복제본을 가진 서비스에 알린다 — {@code user-updated}, {@code user-deleted} (docs/48 §6-⑤).
 *
 * <p>{@code user-created} 만 있던 동안 복제본은 <b>가입 순간의 값에서 멈춰 있었다</b>: 닉네임·프로필 사진을 바꿔도
 * 채팅에는 옛 값이 보였고, 탈퇴해 원본을 익명화해도 chat 의 복제본에는 이메일이 남았다.
 *
 * <p>아웃박스 릴레이({@code UserOutboxScheduler})가 부른다 — 그래서 <b>동기</b>로 기다려 결과를 돌려준다(행의 상태를
 * 확정해야 한다). 페이로드는 JSON 문자열이고 받는 쪽은 필드 이름으로 읽는다(알림 이벤트와 같은 모양).
 * 트랜잭션 프로듀서가 아니다 — 릴레이의 DB 트랜잭션과 묶을 이유가 없다(행 상태가 곧 발행 기록이다).
 */
@Slf4j
@Component
public class UserLifecycleProducer {

    public static final String UPDATED = "user-updated";
    public static final String DELETED = "user-deleted";

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${app.outbox.publish-timeout-ms:10000}")
    private long publishTimeoutMs = 10_000;

    /**
     * 🐛 {@code @Autowired} 가 없었다. 생성자가 둘(이것과 테스트용)이면 스프링은 어느 것을 쓸지 모르고 기본 생성자를 찾다
     * <b>"No default constructor found"</b> 로 기동에 실패한다 — 단위 테스트는 생성자를 직접 불러 잡지 못했다(2026-09-26 배포에서
     * user-service 가 기동하지 못한 원인). 가드 B-65 가 다시 막는다.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public UserLifecycleProducer(@Qualifier("producerConfig") Map<String, Object> baseProducerConfig,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.observation.ObservationRegistry> observationRegistry) {
        Map<String, Object> config = new HashMap<>(baseProducerConfig);
        config.remove(ProducerConfig.TRANSACTIONAL_ID_CONFIG);
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        this.kafkaTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config,
                new StringSerializer(), new StringSerializer()));
        // 빈이 아닌 템플릿이라 공통 후처리기가 닿지 않는다 — 추적을 직접 켠다(docs/51 §4d).
        observationRegistry.ifAvailable(registry -> {
            this.kafkaTemplate.setObservationRegistry(registry);
            this.kafkaTemplate.setObservationEnabled(true);
        });
    }

    /** 테스트용 — 템플릿을 직접 넣는다. */
    UserLifecycleProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /** 키 = 사용자 id — 같은 사용자의 created·updated·deleted 가 한 파티션에서 순서대로 읽힌다. */
    public void publish(String topic, String userId, String json) {
        try {
            kafkaTemplate.send(topic, userId, json).get(publishTimeoutMs, TimeUnit.MILLISECONDS);
            log.info("{} 발행: userId={}", topic, userId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(topic + " 발행이 중단됐다: userId=" + userId, e);
        } catch (Exception e) {
            // 이름·이미지 값은 남기지 않는다. userId 로 충분히 추적된다.
            throw new IllegalStateException(topic + " 발행 실패: userId=" + userId, e);
        }
    }
}
