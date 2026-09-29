package com.project.common.kafka.avro;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;

/**
 * 값을 <b>JSON 문자열</b>로 받는 소비자용 — delivery·notification 은 {@code List<String>} 을 받아
 * {@code JsonNode} 로 필드 몇 개만 꺼내 쓴다. 이들에게 Avro 바이트를 그대로 주면 깨지므로, 토픽에
 * 디코더가 있고 Avro 면 레코드를 평범한 JSON 으로 바꿔 주고({@link SpecificRecordJson}), 아니면
 * 바이트를 문자열로 그대로 돌려준다. 소비자 코드는 손대지 않는다.
 *
 * <p>DTO 가 필요 없다 — 스키마에서 생성된 클래스만으로 JSON 을 만든다. 그래서 chat 처럼 DTO 가
 * common 밖에 있는 토픽도 여기서 읽을 수 있다.
 */
public class AvroJsonTextDeserializer implements Deserializer<String> {

    private final Map<String, BinaryMessageDecoder<? extends SpecificRecord>> byTopic;

    /** 토픽 → 디코더. 기본은 {@link AvroDecoders#byTopic()} — 스키마가 있는 모든 토픽. */
    public AvroJsonTextDeserializer() {
        this(AvroDecoders.byTopic());
    }

    public AvroJsonTextDeserializer(Map<String, BinaryMessageDecoder<? extends SpecificRecord>> byTopic) {
        this.byTopic = byTopic;
    }

    @Override
    public String deserialize(String topic, byte[] data) {
        if (data == null) {
            return null;
        }
        BinaryMessageDecoder<? extends SpecificRecord> decoder = byTopic.get(topic);
        if (decoder == null || !AvroWire.isAvro(data)) {
            return new String(data, StandardCharsets.UTF_8);
        }
        try {
            return SpecificRecordJson.toJson(decoder.decode(ByteBuffer.wrap(data)));
        } catch (IOException e) {
            throw new SerializationException("Avro → JSON 변환 실패: " + topic, e);
        }
    }
}
