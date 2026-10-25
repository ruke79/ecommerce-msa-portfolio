package com.project.chat_service.common.config.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 회원 복제 이벤트는 오프셋이 없을 때 처음부터 읽는다 — 가입 · 탈퇴 모두.
 *
 * <p>🐛 예전: 가입(user-3)만 latest. 그룹 오프셋이 만료되거나 새 클러스터에서 처음 뜨면 그 사이의 가입 이벤트를 건너뛰어 그 회원은
 * 임시 닉네임으로만 남았다(N-M5 · D-M7 과 같은 종류).
 *
 * <p>[JA] 会員複製イベントはオフセットがなければ最初から読む。以前は加入だけ latest で、その間の加入を飛ばした。
 */
@DisplayName("chat — 회원 복제 컨슈머 오프셋 기본값")
class KafkaOffsetResetTest {

    private static KafkaConsumerConfig config() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "environment", new MockEnvironment());
        ReflectionTestUtils.setField(config, "kafkaServerUrl", "localhost:9092");
        return config;
    }

    @Test
    @DisplayName("🔴 user-created(그룹 user-3)는 earliest")
    void userCreatedReadsFromTheStart() {
        assertThat(config().userCreatedConsumerFactory().getConfigurationProperties())
                .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "user-3")
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    }

    @Test
    @DisplayName("user-updated · user-deleted 도 earliest")
    void lifecycleReadsFromTheStart() {
        assertThat(config().userLifecycleConsumerFactory().getConfigurationProperties())
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    }

    @Test
    @DisplayName("값 역직렬화기는 생성자 인스턴스만 — 맵에는 쓰이지 않는 값을 두지 않는다")
    void noIgnoredValueDeserializerInTheMap() {
        KafkaConsumerConfig c = config();
        assertThat(c.consumerFactory().getConfigurationProperties())
                .doesNotContainKey(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);
        assertThat(c.userCreatedConsumerFactory().getConfigurationProperties())
                .doesNotContainKey(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);
    }
}
