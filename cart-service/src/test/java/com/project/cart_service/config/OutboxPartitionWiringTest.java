package com.project.cart_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.project.common.outbox.partition.OutboxPartitionAutoConfiguration;
import com.project.common.outbox.partition.OutboxPartitionMaintainer;

/**
 * 파티션 잡(common OutboxPartitionAutoConfiguration)이 이 서비스 설정으로 <b>실제로 켜지는가</b>.
 *
 * <p>설정 키는 B-70 이 보지만, 자동 설정이 빈을 만드는지는 컨텍스트를 띄워야 안다. 이 테스트를 처음 돌렸을 때
 * {@code @Value Duration} 변환이 실패해 빈 생성이 깨졌다(변환 서비스가 없는 컨텍스트) — 문자열로 받아 직접 읽게 고쳤다.
 */
class OutboxPartitionWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, OutboxPartitionAutoConfiguration.class))
            .withPropertyValues("spring.datasource.url=jdbc:h2:mem:wiring", "outbox.partitions.initial-delay=PT1H");

    @Test
    @org.junit.jupiter.api.DisplayName("outbox.partitions.tables 가 있으면 잡이 켜진다")
    void activatesWithTables() {
        runner.withPropertyValues("outbox.partitions.tables=cart.outbox_event")
                .run(ctx -> assertThat(ctx).hasSingleBean(OutboxPartitionMaintainer.class));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("tables 가 없으면 켜지지 않는다(파티션이 없는 서비스)")
    void inactiveWithoutTables() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(OutboxPartitionMaintainer.class));
    }
}
