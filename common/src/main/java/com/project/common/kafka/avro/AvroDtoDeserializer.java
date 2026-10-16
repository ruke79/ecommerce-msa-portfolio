package com.project.common.kafka.avro;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Deserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

/**
 * Avro 단일 객체 인코딩이면 Avro 로, 아니면 JSON 으로 읽어 <b>같은 DTO</b> 를 돌려준다.
 *
 * <p>두 형식을 동시에 받는 이유(docs/38 §1.16):
 * <ul>
 *   <li>롤링 배포 — 프로듀서가 먼저 Avro 로 바뀌든 소비자가 먼저 바뀌든, 그 사이 토픽에는 두 형식이
 *       섞여 있다. 소비자가 둘 다 읽어야 어느 순서로 배포해도 안전하다.</li>
 *   <li>Debezium CDC 릴레이 — Connect 는 Avro 컨버터(Confluent) 없이는 문자열(JSON)만 낸다.</li>
 *   <li>e2e 테스트와 수동 발행 — JSON 을 손으로 쓰는 것이 훨씬 쉽다.</li>
 * </ul>
 * JSON 쪽은 spring-kafka 의 {@link JsonDeserializer} 에 그대로 맡긴다 — 이 저장소가 지금까지 쓰던
 * 바로 그 역직렬화기라 날짜 형식·헤더 처리가 예전과 같다.
 */
public class AvroDtoDeserializer<D> implements Deserializer<D> {

    private final AvroMapping<D, ? extends SpecificRecord> mapping;
    private final JsonDeserializer<D> json;

    private final org.apache.avro.message.BinaryMessageDecoder<? extends SpecificRecord> decoder;

    public AvroDtoDeserializer(AvroMapping<D, ? extends SpecificRecord> mapping) {
        this.mapping = mapping;
        // 디코더는 한 번만 만든다(BinaryMessageDecoder 는 스레드 안전하다). 🐛 예전에는 레코드마다 mapping.decoder() 를 불러, 매핑
        // 대부분이 그 안에서 avsc 이력을 클래스패스에서 읽고 파싱해 새 디코더를 만들었다 — 옛 작성 스키마로 쓴 레코드는 해석 문법까지
        // 매번 다시 만들었다(소비 핫 경로). 이력이 없으면 여기서 실패한다 — 기동할 때 드러난다.
        // [JA] デコーダーは一度だけ作る。以前はレコードごとに mapping.decoder() を呼び、avsc 履歴の読み込み・解析から作り直していた。
        this.decoder = mapping.decoder();
        // 헤더(__TypeId__)가 있으면 쓰고 없으면 dtoType — 컨슈머 설정들과 같은 규칙(docs/38 §5 A3).
        this.json = new JsonDeserializer<>(mapping.dtoType(), true);
        this.json.addTrustedPackages("*");
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        json.configure(configs, isKey);
    }

    @Override
    public D deserialize(String topic, byte[] data) {
        return deserialize(topic, null, data);
    }

    @Override
    public D deserialize(String topic, Headers headers, byte[] data) {
        if (data == null) {
            return null;
        }
        if (AvroWire.isAvro(data)) {
            try {
                return decode(data);
            } catch (IOException e) {
                throw new SerializationException("Avro 역직렬화 실패: " + topic + " → " + mapping.dtoType().getSimpleName(), e);
            }
        }
        return headers == null ? json.deserialize(topic, data) : json.deserialize(topic, headers, data);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private D decode(byte[] data) throws IOException {
        AvroMapping raw = mapping;
        SpecificRecord record = (SpecificRecord) decoder.decode(ByteBuffer.wrap(data));
        return (D) raw.toDto(record);
    }

    @Override
    public void close() {
        json.close();
    }
}
