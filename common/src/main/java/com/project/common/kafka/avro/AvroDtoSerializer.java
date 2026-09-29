package com.project.common.kafka.avro;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.avro.specific.SpecificRecord;

/**
 * DTO 를 Avro 단일 객체 인코딩으로 쓴다. 헤더 없음, 레지스트리 없음 — 지문이 레코드 안에 있다.
 *
 * <p>Kafka 의 {@code Serializer} 는 보통 클래스 이름으로 등록해 무인자 생성되지만, 이 직렬화기는
 * 매핑을 안고 있어야 하므로 <b>인스턴스</b>로 {@code DefaultKafkaProducerFactory} 에 넘긴다 —
 * 이 저장소의 역직렬화기들이 이미 그렇게 등록돼 있다.
 */
public class AvroDtoSerializer<D> implements Serializer<D> {

    private final AvroMapping<D, ? extends SpecificRecord> mapping;

    public AvroDtoSerializer(AvroMapping<D, ? extends SpecificRecord> mapping) {
        this.mapping = mapping;
    }

    @Override
    public byte[] serialize(String topic, D dto) {
        if (dto == null) {
            return null;
        }
        try {
            ByteBuffer buffer = encode(dto);
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException("Avro 직렬화 실패: " + mapping.dtoType().getSimpleName() + " → " + topic, e);
        } catch (RuntimeException e) {
            throw new SerializationException("Avro 직렬화 실패: " + mapping.dtoType().getSimpleName() + " → " + topic, e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ByteBuffer encode(D dto) throws IOException {
        AvroMapping raw = mapping;
        return raw.encoder().encode(raw.toRecord(dto));
    }
}
