package com.project.saga_projection.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.kafka.streams.KafkaStreams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;

import com.project.saga_projection.config.SagaProjectionProperties;

@DisplayName("SagaQueryService · 준비 상태 판정")
class SagaQueryServiceTest {

    private static final SagaProjectionProperties PROPS = new SagaProjectionProperties(
            "saga-projection", "localhost:8190", "./state", 1,
            new SagaProjectionProperties.Topics("order", "payment", "order-saga-state"));

    @Test
    @DisplayName("Streams 가 아직 없으면 NOT_READY — 503 + Retry-After")
    void notReadyBeforeStreamsExists() {
        StreamsBuilderFactoryBean fb = mock(StreamsBuilderFactoryBean.class);
        when(fb.getKafkaStreams()).thenReturn(null);
        assertThat(new SagaQueryService(fb, PROPS).find(1).answer()).isEqualTo(SagaQueryService.Answer.NOT_READY);
    }

    @Test
    @DisplayName("RUNNING·REBALANCING 만 조회 가능하다 — 내려간 클라이언트는 빈 답이 아니라 503 이어야 한다")
    void onlyRunningOrRebalancingIsQueryable() {
        assertThat(SagaQueryService.isQueryable(KafkaStreams.State.RUNNING)).isTrue();
        assertThat(SagaQueryService.isQueryable(KafkaStreams.State.REBALANCING)).isTrue();
        assertThat(SagaQueryService.isQueryable(KafkaStreams.State.ERROR)).isFalse();
        assertThat(SagaQueryService.isQueryable(KafkaStreams.State.NOT_RUNNING)).isFalse();
        assertThat(SagaQueryService.isQueryable(KafkaStreams.State.CREATED)).isFalse();
    }

    @Test
    @DisplayName("ERROR 상태의 클라이언트는 NOT_READY")
    void erroredStreamsIsNotReady() {
        StreamsBuilderFactoryBean fb = mock(StreamsBuilderFactoryBean.class);
        KafkaStreams ks = mock(KafkaStreams.class);
        when(ks.state()).thenReturn(KafkaStreams.State.ERROR);
        when(fb.getKafkaStreams()).thenReturn(ks);
        assertThat(new SagaQueryService(fb, PROPS).find(1).answer()).isEqualTo(SagaQueryService.Answer.NOT_READY);
    }
}
