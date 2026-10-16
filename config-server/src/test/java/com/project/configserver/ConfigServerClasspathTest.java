package com.project.configserver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.ClassUtils;

/**
 * config-server 클래스패스에 Spring Cloud Bus · RabbitMQ 가 없다.
 *
 * <p>🐛 예전: {@code spring-cloud-starter-bus-amqp} 가 있었는데 RabbitMQ 는 어디에도 없었다 — 켜 두면 브로커 재연결을 반복하고
 * {@code /actuator/health} 가 DOWN 이라, 매니페스트가 버스와 rabbit 헬스를 env 로 꺼 두기만 했다. 쓰지 않는 의존성이 이미지에 들어 있었다.
 *
 * <p>[JA] config-server のクラスパスに Spring Cloud Bus · RabbitMQ がない。以前は RabbitMQ がないのに依存があり、マニフェストが
 * env で止めていただけだった。
 */
@DisplayName("config-server — 쓰지 않는 버스 의존성 없음")
class ConfigServerClasspathTest {

    @Test
    @DisplayName("🔴 Spring Cloud Bus · Spring AMQP 클래스가 클래스패스에 없다")
    void noBusOrAmqp() {
        ClassLoader cl = getClass().getClassLoader();
        assertThat(ClassUtils.isPresent("org.springframework.cloud.bus.BusAutoConfiguration", cl)).isFalse();
        assertThat(ClassUtils.isPresent("org.springframework.amqp.rabbit.core.RabbitTemplate", cl)).isFalse();
    }
}
