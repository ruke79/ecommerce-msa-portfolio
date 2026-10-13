package com.project.saga_projection.topology;

import java.time.Clock;

import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.KeyValueStore;

import com.project.common.avro.OrderSagaState;
import com.project.common.avro.OrderStatusChanged;
import com.project.common.avro.OrderTimeline;
import com.project.common.kafka.avro.AvroDtoDeserializer;
import com.project.common.kafka.avro.AvroDtoSerializer;
import com.project.common.kafka.avro.AvroSpecificSerde;
import com.project.common.kafka.avro.PaymentRequestAvroMapping;
import com.project.common.kafka.avro.PaymentResponseAvroMapping;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.message.dto.response.PaymentResponse;

/**
 * 토폴로지: {@code order} ∪ {@code payment} → (주문 id 로 재키잉) → 집계 → KTable {@code order-saga-state}.
 *
 * <pre>
 *   order   ──AvroDto(PaymentRequest)──┐
 *                                      ├─ map(orderId, delta) ─ merge ─ groupByKey ─ aggregate ─┬─ store  (interactive query)
 *   payment ──AvroDto(PaymentResponse)─┘                                                        └─ toStream ─ to(order-saga-state)
 * </pre>
 *
 * <p><b>재키잉하는 이유.</b> 두 토픽의 키는 둘 다 주문 id 지만 발행 지점이 다르다 — order 의 릴레이는
 * {@code "123"}, Debezium CDC 는 aggregate_id 를 그대로 싣는다. 같은 주문이 같은 태스크에 모이려면
 * 정규화된 키({@code String.valueOf(orderId)})로 한 번 리파티션해야 한다. 리파티션 토픽도 EOS 트랜잭션
 * 안이다.
 *
 * <p><b>입력은 하이브리드로 읽는다.</b> {@link AvroDtoDeserializer} 가 Avro(마커 {@code C3 01})와 JSON 을
 * 둘 다 받으므로, 프로듀서가 아직 JSON 이거나 CDC 릴레이(문자열)여도 프로젝션은 접힌다(docs/38 §1.16).
 *
 * <p>[JA] トポロジー: {@code order} ∪ {@code payment} → (注文 id で再キーイング) → 集約 → KTable {@code order-saga-state}。
 * (データフロー図は上の {@code <pre>} を参照。図中のラベルは英語のみで翻訳不要。)
 *
 * <p><b>再キーイングする理由。</b> 二つのトピックのキーはどちらも注文 id だが、発行元が異なる — order のリレーは
 * {@code "123"} を、Debezium CDC は aggregate_id をそのまま載せる。同じ注文を同じタスクに集めるには、
 * 正規化したキー({@code String.valueOf(orderId)})で一度リパーティションする必要がある。リパーティショントピックも
 * EOS トランザクションの内側である。
 *
 * <p><b>入力はハイブリッドで読む。</b> {@link AvroDtoDeserializer} が Avro(マーカー {@code C3 01})と JSON の
 * 両方を受け付けるので、プロデューサがまだ JSON であっても、CDC リレー(文字列)であっても、プロジェクションは
 * 畳み込まれる(docs/38 §1.16)。
 */
public final class SagaTopology {

    public static final String STORE_NAME = "order-saga-state-store";
    /** 주문 진행 타임라인 저장소(docs/76) — 키는 사가 저장소와 같은 주문 id 문자열. */
    public static final String TIMELINE_STORE_NAME = "order-timeline-store";

    private final String orderTopic;
    private final String paymentTopic;
    private final String stateTopic;
    private final String orderStatusTopic;
    private final String timelineTopic;
    private final SagaEvents events;
    private final Clock clock;

    public SagaTopology(String orderTopic, String paymentTopic, String stateTopic) {
        this(orderTopic, paymentTopic, stateTopic, Clock.systemUTC());
    }

    public SagaTopology(String orderTopic, String paymentTopic, String stateTopic, Clock clock) {
        this(orderTopic, paymentTopic, stateTopic, "order-status", "order-timeline", clock);
    }

    public SagaTopology(String orderTopic, String paymentTopic, String stateTopic, String orderStatusTopic,
            String timelineTopic) {
        this(orderTopic, paymentTopic, stateTopic, orderStatusTopic, timelineTopic, Clock.systemUTC());
    }

    public SagaTopology(String orderTopic, String paymentTopic, String stateTopic, String orderStatusTopic,
            String timelineTopic, Clock clock) {
        this.orderTopic = orderTopic;
        this.paymentTopic = paymentTopic;
        this.stateTopic = stateTopic;
        this.orderStatusTopic = orderStatusTopic;
        this.timelineTopic = timelineTopic;
        this.events = new SagaEvents(clock);
        this.clock = clock;
    }

    public static Serde<OrderSagaState> stateSerde() {
        return new AvroSpecificSerde<>(OrderSagaState.getEncoder(), OrderSagaState.getDecoder());
    }

    /** 입력 {@code order-status} — order-service 의 발행기와 같은 단일 객체 인코딩(docs/76). */
    public static Serde<OrderStatusChanged> orderStatusSerde() {
        return new AvroSpecificSerde<>(OrderStatusChanged.getEncoder(), OrderStatusChanged.getDecoder());
    }

    public static Serde<OrderTimeline> timelineSerde() {
        return new AvroSpecificSerde<>(OrderTimeline.getEncoder(), OrderTimeline.getDecoder());
    }

    public void build(StreamsBuilder builder) {
        Serde<PaymentRequest> requestSerde = Serdes.serdeFrom(
                new AvroDtoSerializer<>(new PaymentRequestAvroMapping()),
                new AvroDtoDeserializer<>(new PaymentRequestAvroMapping()));
        Serde<PaymentResponse> responseSerde = Serdes.serdeFrom(
                new AvroDtoSerializer<>(new PaymentResponseAvroMapping()),
                new AvroDtoDeserializer<>(new PaymentResponseAvroMapping()));
        Serde<OrderSagaState> stateSerde = stateSerde();

        KStream<String, OrderSagaState> requests = builder
                .stream(orderTopic, Consumed.with(Serdes.String(), requestSerde).withName("order-requests"))
                .filter((k, v) -> v != null && v.getOrderId() != null, Named.as("drop-requests-without-order-id"))
                .map((k, v) -> KeyValue.pair(String.valueOf(v.getOrderId()), events.fromRequest(v)), Named.as("request-delta"));

        KStream<String, OrderSagaState> responses = builder
                .stream(paymentTopic, Consumed.with(Serdes.String(), responseSerde).withName("payment-responses"))
                .filter((k, v) -> v != null && v.getOrderId() != null, Named.as("drop-responses-without-order-id"))
                .map((k, v) -> KeyValue.pair(String.valueOf(v.getOrderId()), events.fromResponse(v)), Named.as("response-delta"));

        KTable<String, OrderSagaState> state = requests
                .merge(responses, Named.as("saga-deltas"))
                .groupByKey(Grouped.with("saga-by-order", Serdes.String(), stateSerde))
                .aggregate(() -> null, (orderId, delta, current) -> SagaStateReducer.apply(current, delta),
                        Named.as("fold-saga-state"),
                        Materialized.<String, OrderSagaState, KeyValueStore<Bytes, byte[]>>as(STORE_NAME)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(stateSerde));

        state.toStream(Named.as("saga-state-changes"))
                .to(stateTopic, Produced.with(Serdes.String(), stateSerde).withName("to-order-saga-state"));

        // ── 주문 진행 타임라인(docs/76) — 사가와 별개의 저장소다. 같은 레코드(OrderSagaState)에 넣으면 그 스키마를
        //    바꿔야 하고, 컴팩션 토픽 · 변경 로그 · RocksDB 에 쌓인 옛 지문의 레코드를 전부 읽을 수 있어야 한다(docs/55 §4).
        //    새 저장소면 그 문제가 없다. 조회 API 가 둘을 합친다.
        // [JA] 注文進行タイムライン(docs/76) — サーガとは別のストア。同じレコードに入れるとスキーマ進化が要る。新しいストアなら不要。
        Serde<OrderStatusChanged> orderStatusSerde = orderStatusSerde();
        Serde<OrderTimeline> timelineSerde = timelineSerde();

        KTable<String, OrderTimeline> timeline = builder
                .stream(orderStatusTopic, Consumed.with(Serdes.String(), orderStatusSerde).withName("order-status-changes"))
                .filter((k, v) -> v != null && v.getEventId() != null && v.getStatus() != null,
                        Named.as("drop-status-changes-without-id"))
                .map((k, v) -> KeyValue.pair(String.valueOf(v.getOrderId()), OrderTimelineReducer.delta(v, clock.instant())),
                        Named.as("status-delta"))
                .groupByKey(Grouped.with("timeline-by-order", Serdes.String(), timelineSerde))
                .aggregate(() -> null, (orderId, delta, current) -> OrderTimelineReducer.apply(current, delta),
                        Named.as("fold-timeline"),
                        Materialized.<String, OrderTimeline, KeyValueStore<Bytes, byte[]>>as(TIMELINE_STORE_NAME)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(timelineSerde));

        timeline.toStream(Named.as("timeline-changes"))
                .to(timelineTopic, Produced.with(Serdes.String(), timelineSerde).withName("to-order-timeline"));
    }
}
