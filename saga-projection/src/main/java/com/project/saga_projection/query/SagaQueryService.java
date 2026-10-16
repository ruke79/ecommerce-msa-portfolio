package com.project.saga_projection.query;

import java.util.Optional;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyQueryMetadata;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.HostInfo;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Service;

import com.project.common.avro.OrderSagaState;
import com.project.common.avro.OrderTimeline;
import com.project.saga_projection.config.SagaProjectionProperties;
import com.project.saga_projection.topology.SagaTopology;

import lombok.extern.slf4j.Slf4j;

/**
 * 대화형 조회(interactive query) — 상태 저장소를 HTTP 로 연다.
 *
 * <p><b>다른 파드로 넘기지 않는다.</b> 대신 {@code num.standby.replicas} 로 저장소 사본을 파드마다 두고
 * ({@code enableStaleStores}) 로컬에서 답한다. 포워딩은 파드 IP 로 HTTPS 를 걸어야 하는데 인증서 SAN 이
 * 서비스 DNS 뿐이라 검증에 실패한다(k8s/48 의 EUREKA_INSTANCE_HOSTNAME 주석과 같은 이유). 레플리카 수가
 * standby+1 을 넘으면 이 파드에 사본이 없는 키가 생기고, 그때는 {@link Answer#NOT_HERE} 로 503 을 낸다 —
 * 조용히 "없음"으로 답하는 것보다 낫다. 입력 토픽이 파티션 하나인 지금은 하위 토폴로지마다 태스크가 하나라(사가 · 타임라인) 레플리카 2 · standby 1 이면 항상 로컬이다.
 */
@Slf4j
@Service
public class SagaQueryService {

    public enum Answer { FOUND, NOT_FOUND, NOT_READY, NOT_HERE }

    public record Result(Answer answer, OrderSagaView view, String activeHost) {
        static Result of(Answer a) {
            return new Result(a, null, null);
        }
    }

    private final StreamsBuilderFactoryBean factory;
    private final HostInfo self;

    public SagaQueryService(StreamsBuilderFactoryBean factory, SagaProjectionProperties props) {
        this.factory = factory;
        this.self = HostInfo.buildFromEndpoint(props.applicationServer());
    }

    /** 소유권 검사 없음 — 내부 진단용. 게이트웨이를 거친 요청은 {@link #find(long, Long)} 로 온다. */
    public Result find(long orderId) {
        return find(orderId, null);
    }

    /**
     * @param requesterId 게이트웨이가 쓴 {@code User-Id}. null 이 아니면 주문의 customerId 와 같아야 한다 — 다르면
     *                    <b>NOT_FOUND</b>(남의 자원은 403 이 아니라 404). customerId 를 모르는 레코드도
     *                    남의 것으로 본다.
     * [JA] requesterId が null でなければ注文の customerId と一致しなければならない — 違えば NOT_FOUND(他人の資源は 404)。
     */
    public Result find(long orderId, Long requesterId) {
        KafkaStreams streams = factory.getKafkaStreams();
        if (streams == null || !isQueryable(streams.state())) {
            return Result.of(Answer.NOT_READY);
        }
        String key = String.valueOf(orderId);

        // 저장소마다 따로 판정한다. 두 집계는 재분할 토픽이 달라(saga-by-order · timeline-by-order) 서로 다른 하위 토폴로지의
        // 태스크이고, 파티션 수가 같아도 같은 파드에 배정된다는 보장이 없다.
        // 🐛 예전: 사가 저장소의 메타데이터만 봤다 — 타임라인 사본이 없는 파드가 enableStaleStores 로 빈 저장소를 읽어 "진행 이력
        //    없음"으로 조용히 답할 수 있었다(레플리카 > standby+1 일 때). 지금(레플리카 2 · standby 1)은 파드마다 둘 다 있다.
        // [JA] ストアごとに判定する。二つの集約は再パーティショントピックが異なり別サブトポロジーのタスクで、同じ Pod に載る保証がない。
        //      以前はサガのメタデータだけを見ていて、タイムラインの写しがない Pod が「履歴なし」と黙って答えることがあった。
        KeyQueryMetadata sagaMeta = streams.queryMetadataForKey(SagaTopology.STORE_NAME, key, Serdes.String().serializer());
        KeyQueryMetadata timelineMeta = streams.queryMetadataForKey(SagaTopology.TIMELINE_STORE_NAME, key, Serdes.String().serializer());
        if (unavailable(sagaMeta) || unavailable(timelineMeta)) {
            return Result.of(Answer.NOT_READY);
        }
        for (KeyQueryMetadata meta : new KeyQueryMetadata[] { sagaMeta, timelineMeta }) {
            if (!self.equals(meta.activeHost()) && !meta.standbyHosts().contains(self)) {
                log.warn("주문 {} 의 상태 사본이 이 인스턴스({})에 없다 — 활성 {}, 대기 {}. 레플리카 > standby+1 인가?",
                        orderId, self, meta.activeHost(), meta.standbyHosts());
                return new Result(Answer.NOT_HERE, null, meta.activeHost().host() + ":" + meta.activeHost().port());
            }
        }
        // 둘 중 하나라도 대기 사본에서 읽으면 늦을 수 있다
        boolean stale = !self.equals(sagaMeta.activeHost()) || !self.equals(timelineMeta.activeHost());

        try {
            ReadOnlyKeyValueStore<String, OrderSagaState> sagaStore = streams.store(
                    StoreQueryParameters.fromNameAndType(SagaTopology.STORE_NAME,
                            QueryableStoreTypes.<String, OrderSagaState>keyValueStore()).enableStaleStores());
            ReadOnlyKeyValueStore<String, OrderTimeline> timelineStore = streams.store(
                    StoreQueryParameters.fromNameAndType(SagaTopology.TIMELINE_STORE_NAME,
                            QueryableStoreTypes.<String, OrderTimeline>keyValueStore()).enableStaleStores());
            return compose(sagaStore.get(key), timelineStore.get(key), requesterId, stale);
        } catch (InvalidStateStoreException e) {
            // 리밸런스 중이거나 저장소를 아직 복구하는 중이다 — 잠깐 뒤 다시.
            log.debug("상태 저장소를 아직 열 수 없다: {}", e.getMessage());
            return Result.of(Answer.NOT_READY);
        }
    }

    /** 저장소 둘 → 답. 순수 함수라 Streams 없이 검증한다. */
    static Result compose(OrderSagaState saga, OrderTimeline timeline, Long requesterId, boolean stale) {
        if (saga == null && timeline == null) {
            return Result.of(Answer.NOT_FOUND);
        }
        OrderSagaView view = OrderSagaView.of(saga, timeline, stale);
        if (requesterId != null && !requesterId.equals(view.customerId())) {
            // 남의 주문 — 있는지조차 알리지 않는다(진짜 없음과 같은 답).
            return Result.of(Answer.NOT_FOUND);
        }
        return new Result(Answer.FOUND, view, null);
    }

    private static boolean unavailable(KeyQueryMetadata meta) {
        return meta == null || meta.equals(KeyQueryMetadata.NOT_AVAILABLE);
    }

    static boolean isQueryable(KafkaStreams.State state) {
        return state == KafkaStreams.State.RUNNING || state == KafkaStreams.State.REBALANCING;
    }
}
