package com.project.saga_projection.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyQueryMetadata;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.state.HostInfo;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import com.project.common.avro.OrderSagaState;
import com.project.common.avro.OrderTimeline;
import com.project.common.avro.OrderTimelineEntry;
import com.project.common.avro.SagaPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;

import com.project.saga_projection.config.SagaProjectionProperties;
import com.project.saga_projection.topology.SagaTopology;

@DisplayName("SagaQueryService · 준비 상태 판정")
class SagaQueryServiceTest {

    private static final SagaProjectionProperties PROPS = new SagaProjectionProperties(
            "saga-projection", "localhost:8190", "./state", 1,
            new SagaProjectionProperties.Topics("order", "payment", "order-saga-state", "order-status", "order-timeline"));

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

    // ---------------------------------------------------------------- 두 저장소 합치기 · 소유권 (docs/76)

    private static OrderSagaState saga(long customerId) {
        return OrderSagaState.newBuilder().setOrderId(1L).setPhase(SagaPhase.COMPLETED).setCustomerId(customerId)
                .setUpdatedAt(Instant.parse("2026-10-07T00:00:00Z")).build();
    }

    private static OrderTimeline timeline(Long customerId, String status) {
        return OrderTimeline.newBuilder().setOrderId(1L).setCustomerId(customerId).setStatus(status)
                .setEntries(List.of(OrderTimelineEntry.newBuilder().setEventId("e").setStatus(status).setSource("test")
                        .setOccurredAt(Instant.parse("2026-10-07T00:00:10Z")).build()))
                .setEventCount(1).setUpdatedAt(Instant.parse("2026-10-07T00:00:10Z")).build();
    }

    @Test
    @DisplayName("사가 상태와 타임라인을 한 응답으로 — 둘 중 하나만 있어도 FOUND")
    void composesBothStores() {
        SagaQueryService.Result both = SagaQueryService.compose(saga(7L), timeline(7L, "PAID"), 7L, false);
        assertThat(both.answer()).isEqualTo(SagaQueryService.Answer.FOUND);
        assertThat(both.view().phase()).isEqualTo("COMPLETED");
        assertThat(both.view().orderStatus()).isEqualTo("PAID");
        assertThat(both.view().timeline()).hasSize(1);
        assertThat(both.view().updatedAt()).isEqualTo(Instant.parse("2026-10-07T00:00:10Z"));

        SagaQueryService.Result sagaOnly = SagaQueryService.compose(saga(7L), null, 7L, true);
        assertThat(sagaOnly.view().orderStatus()).isNull();
        assertThat(sagaOnly.view().timeline()).isEmpty();
        assertThat(sagaOnly.view().stale()).isTrue();

        SagaQueryService.Result timelineOnly = SagaQueryService.compose(null, timeline(7L, "NOT_PROCESSED"), 7L, false);
        assertThat(timelineOnly.view().phase()).isNull();
        assertThat(timelineOnly.view().orderStatus()).isEqualTo("NOT_PROCESSED");

        assertThat(SagaQueryService.compose(null, null, 7L, false).answer()).isEqualTo(SagaQueryService.Answer.NOT_FOUND);
    }

    @Test
    @DisplayName("🔴 남의 주문은 NOT_FOUND — 403 이 아니라 404, 있는지조차 알리지 않는다 ")
    void someoneElsesOrderLooksLikeNotFound() {
        SagaQueryService.Result r = SagaQueryService.compose(saga(7L), timeline(7L, "PAID"), 8L, false);
        assertThat(r.answer()).isEqualTo(SagaQueryService.Answer.NOT_FOUND);
        assertThat(r.view()).isNull();
        assertThat(r.activeHost()).isNull();
        assertThat(r).isEqualTo(SagaQueryService.compose(null, null, 8L, false));
    }

    @Test
    @DisplayName("customerId 를 모르는 레코드도 남의 것으로 본다 — 모르면 보여 주지 않는다")
    void unknownCustomerIsNotFoundForAnyRequester() {
        assertThat(SagaQueryService.compose(null, timeline(null, "PAID"), 7L, false).answer())
                .isEqualTo(SagaQueryService.Answer.NOT_FOUND);
    }

    @Test
    @DisplayName("요청자 없음(내부 진단)은 소유권을 보지 않는다")
    void noRequesterSkipsOwnership() {
        assertThat(SagaQueryService.compose(saga(7L), null, null, false).answer()).isEqualTo(SagaQueryService.Answer.FOUND);
    }

    // ---------------------------------------------------------------- 저장소마다의 배치 (SP-L1)

    private static final HostInfo SELF = new HostInfo("localhost", 8190);
    private static final HostInfo OTHER = new HostInfo("other", 8190);

    private static KeyQueryMetadata at(HostInfo active,
            HostInfo... standby) {
        return new KeyQueryMetadata(active, Set.of(standby), 0);
    }

    /** 사가 · 타임라인 저장소의 배치를 따로 정한 Streams. 저장소는 둘 다 값을 갖고 있다. */
    @SuppressWarnings("unchecked")
    private static SagaQueryService service(KeyQueryMetadata sagaMeta,
            KeyQueryMetadata timelineMeta) {
        KafkaStreams ks = mock(KafkaStreams.class);
        when(ks.state()).thenReturn(KafkaStreams.State.RUNNING);
        when(ks.queryMetadataForKey(eq(SagaTopology.STORE_NAME), eq("1"),
                any(Serializer.class))).thenReturn(sagaMeta);
        when(ks.queryMetadataForKey(eq(SagaTopology.TIMELINE_STORE_NAME), eq("1"),
                any(Serializer.class))).thenReturn(timelineMeta);
        ReadOnlyKeyValueStore<String, Object> sagaStore =
                mock(ReadOnlyKeyValueStore.class);
        ReadOnlyKeyValueStore<String, Object> timelineStore =
                mock(ReadOnlyKeyValueStore.class);
        when(sagaStore.get("1")).thenReturn(saga(7L));
        when(timelineStore.get("1")).thenReturn(timeline(7L, "PAID"));
        when(ks.store(any())).thenAnswer(inv -> {
            StoreQueryParameters<?> p = inv.getArgument(0);
            return SagaTopology.STORE_NAME.equals(p.storeName()) ? sagaStore : timelineStore;
        });
        StreamsBuilderFactoryBean fb = mock(StreamsBuilderFactoryBean.class);
        when(fb.getKafkaStreams()).thenReturn(ks);
        return new SagaQueryService(fb, PROPS);
    }

    @Test
    @DisplayName("🔴 사가는 이 파드에 있어도 타임라인 사본이 없으면 NOT_HERE — '이력 없음' 으로 조용히 답하지 않는다")
    void timelineElsewhereIsNotHere() {
        SagaQueryService.Result r = service(at(SELF), at(OTHER)).find(1, 7L);
        assertThat(r.answer()).isEqualTo(SagaQueryService.Answer.NOT_HERE);
        assertThat(r.activeHost()).isEqualTo("other:8190");
    }

    @Test
    @DisplayName("🔴 둘 중 하나라도 대기 사본에서 읽으면 stale")
    void staleWhenEitherStoreIsStandby() {
        SagaQueryService.Result r = service(at(SELF), at(OTHER, SELF)).find(1, 7L);
        assertThat(r.answer()).isEqualTo(SagaQueryService.Answer.FOUND);
        assertThat(r.view().orderStatus()).isEqualTo("PAID");
        assertThat(r.view().stale()).isTrue();

        assertThat(service(at(SELF), at(SELF)).find(1, 7L).view().stale()).isFalse();
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
