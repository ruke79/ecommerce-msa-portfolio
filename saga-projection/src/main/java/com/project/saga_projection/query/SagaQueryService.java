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
 * 조용히 "없음"으로 답하는 것보다 낫다. 입력 토픽이 파티션 하나인 지금은 태스크도 하나라 항상 로컬이다.
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

    public Result find(long orderId) {
        KafkaStreams streams = factory.getKafkaStreams();
        if (streams == null || !isQueryable(streams.state())) {
            return Result.of(Answer.NOT_READY);
        }
        String key = String.valueOf(orderId);

        KeyQueryMetadata meta = streams.queryMetadataForKey(SagaTopology.STORE_NAME, key, Serdes.String().serializer());
        if (meta == null || meta.equals(KeyQueryMetadata.NOT_AVAILABLE)) {
            return Result.of(Answer.NOT_READY);
        }
        boolean active = self.equals(meta.activeHost());
        boolean standby = meta.standbyHosts().contains(self);
        if (!active && !standby) {
            log.warn("주문 {} 의 상태 사본이 이 인스턴스({})에 없다 — 활성 {}, 대기 {}. 레플리카 > standby+1 인가?",
                    orderId, self, meta.activeHost(), meta.standbyHosts());
            return new Result(Answer.NOT_HERE, null, meta.activeHost().host() + ":" + meta.activeHost().port());
        }

        try {
            ReadOnlyKeyValueStore<String, OrderSagaState> store = streams.store(
                    StoreQueryParameters.fromNameAndType(SagaTopology.STORE_NAME,
                            QueryableStoreTypes.<String, OrderSagaState>keyValueStore()).enableStaleStores());
            return Optional.ofNullable(store.get(key))
                    .map(s -> new Result(Answer.FOUND, OrderSagaView.of(s, !active), null))
                    .orElse(Result.of(Answer.NOT_FOUND));
        } catch (InvalidStateStoreException e) {
            // 리밸런스 중이거나 저장소를 아직 복구하는 중이다 — 잠깐 뒤 다시.
            log.debug("상태 저장소를 아직 열 수 없다: {}", e.getMessage());
            return Result.of(Answer.NOT_READY);
        }
    }

    static boolean isQueryable(KafkaStreams.State state) {
        return state == KafkaStreams.State.RUNNING || state == KafkaStreams.State.REBALANCING;
    }
}
