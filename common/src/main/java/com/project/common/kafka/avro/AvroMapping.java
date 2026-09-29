package com.project.common.kafka.avro;

import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.message.BinaryMessageEncoder;
import org.apache.avro.specific.SpecificRecord;

/**
 * DTO 하나와 Avro 레코드 하나 사이의 변환.
 *
 * <p><b>왜 리스너가 Avro 클래스가 아니라 DTO 를 계속 받는가.</b> 직렬화기·역직렬화기가 이 변환을
 * 품고 있어서, 리스너와 서비스는 예전 DTO 그대로다. 전선(wire)만 Avro 로 바뀐다. Avro 생성 클래스를
 * 도메인 코드까지 끌고 들어가면 스키마를 바꿀 때마다 서비스 코드가 흔들리고, 생성 클래스의
 * {@code CharSequence}·{@code Instant} 같은 표현이 도메인에 새어 나온다.
 *
 * @param <D> 도메인 DTO
 * @param <R> 스키마에서 생성된 SpecificRecord
 */
public interface AvroMapping<D, R extends SpecificRecord> {

    Class<D> dtoType();

    R toRecord(D dto);

    D toDto(R record);

    /** 생성 클래스의 {@code getEncoder()} — 단일 객체 인코딩(마커 + 지문 + 본문). */
    BinaryMessageEncoder<R> encoder();

    /**
     * 생성 클래스의 {@code getDecoder()} 또는 {@code createDecoder(SchemaStore)}. 기본 디코더는 자기
     * 스키마의 지문만 안다. 옛 스키마로 쓰인 레코드를 읽으려면 {@code SchemaStore} 에 옛 스키마를
     * 등록한 디코더를 돌려주면 된다 — 진화의 핵심이다.
     */
    BinaryMessageDecoder<R> decoder();
}
