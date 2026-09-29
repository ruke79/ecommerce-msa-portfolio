package com.project.notification_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.task.TaskSchedulingProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * 스케줄러 풀 크기가 <b>실제로 읽히는지</b>.
 *
 * <p>이 모듈에는 {@code @Scheduled} 작업이 셋(SMS 발송·메일 발송·메일 스크럽)인데 스프링의
 * 기본 스케줄러는 스레드 하나다({@code TaskSchedulingProperties.Pool.size} 기본값 1 —
 * 바이트코드로 확인). 그래서 {@code application.yml} 에 3 을 적었다.
 *
 * <p>Feign 접두사 사건(docs/37 §2.1)의 교훈대로, 적었다는 것과 읽힌다는 것은 다르다.
 * 여기서는 실제 yml 을 스프링 부트가 쓰는 바로 그 프로퍼티 클래스에 바인딩해 값이
 * 도달하는지 본다. 키를 잘못 적으면(예: {@code spring.task.scheduler}) 기본값 1 이 나온다.
 */
@DisplayName("스케줄러 풀 크기 바인딩")
class SchedulerPoolBindingTest {

    @Test
    @DisplayName("application.yml 의 spring.task.scheduling.pool.size 가 TaskSchedulingProperties 에 도달한다")
    void poolSizeReachesBootProperties() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        assertThat(sources).as("application.yml").isNotEmpty();

        StandardEnvironment env = new StandardEnvironment();
        sources.forEach(env.getPropertySources()::addLast);

        TaskSchedulingProperties props = Binder.get(env)
                .bind("spring.task.scheduling", Bindable.of(TaskSchedulingProperties.class))
                .orElseThrow(() -> new AssertionError("spring.task.scheduling.* 가 바인딩되지 않았다"));

        // @Scheduled 작업 수(3) 이상이어야 한 작업의 외부 호출 대기가 다른 작업을 막지 않는다.
        assertThat(props.getPool().getSize())
                .as("스케줄러 스레드 수 — @Scheduled 작업이 셋이다")
                .isGreaterThanOrEqualTo(3);
        assertThat(props.getThreadNamePrefix()).isEqualTo("sched-");
    }
}
