package com.project.notification_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.notification_service.listener.NotificationEventListener;

/**
 * 오프셋이 없을 때 — 처음부터 읽는다(접속 알림만 지금부터).
 *
 * <p>🐛 예전: latest. 새 리스너를 처음 배포하거나 그룹 오프셋이 만료되면 그 사이의 가입 확인 · 비밀번호 재설정 메일 · 보안 경고가
 * 조용히 사라졌다.
 *
 * <p>[JA] オフセットがないとき — 最初から読む(接続通知だけ今から)。以前は latest で、その間のメールや警告が黙って消えていた。
 */
@DisplayName("notification — 컨슈머 오프셋 기본값")
class KafkaOffsetResetTest {

    @Test
    @DisplayName("🔴 팩토리 기본은 earliest")
    void factoryDefaultsToEarliest() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "environment", new MockEnvironment());
        ReflectionTestUtils.setField(config, "kafkaServerUrl", "localhost:9092");

        assertThat(config.notificationConsumerFactory().getConfigurationProperties())
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    }

    @Test
    @DisplayName("접속 알림(user-presence)만 latest — 지난 접속을 몰아서 알리지 않는다")
    void onlyPresenceIsLatest() {
        for (Method m : NotificationEventListener.class.getDeclaredMethods()) {
            KafkaListener listener = m.getAnnotation(KafkaListener.class);
            if (listener == null) {
                continue;
            }
            boolean latest = Arrays.asList(listener.properties()).contains("auto.offset.reset=latest");
            assertThat(latest).as(listener.id()).isEqualTo(Arrays.asList(listener.topics()).contains("user-presence"));
        }
    }
}
