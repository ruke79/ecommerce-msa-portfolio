package com.project.common.kafka.avro;

/**
 * Avro <b>단일 객체 인코딩</b>(single-object encoding)의 선두 2바이트.
 *
 * <p>스키마 레지스트리 없이 Avro 를 쓰는 방식이다. 레코드 앞에 {@code C3 01} 마커와 작성 스키마의
 * 64비트 지문(fingerprint)이 붙고, 읽는 쪽은 지문으로 작성 스키마를 찾아 자기 스키마와 맞춰
 * 읽는다(스키마 진화). Confluent 레지스트리 방식은 대신 {@code 00} + 4바이트 스키마 id 를 붙이고
 * 레지스트리에 물어본다 — 이 환경은 레지스트리 서버도, 그 클라이언트 라이브러리도 없다
 * (프록시가 packages.confluent.io 를 막는다, docs/38 §1.16).
 *
 * <p>마커 덕에 <b>JSON 과 섞여 있어도 구분된다.</b> JSON 은 {@code '{'}(0x7B)로 시작한다. 그래서
 * 소비자는 두 형식을 동시에 받는다 — 롤링 배포 중 옛 프로듀서의 JSON, e2e 테스트의 JSON, 그리고
 * Debezium CDC 릴레이의 JSON(Connect 는 Avro 컨버터 없이는 문자열이다).
 */
public final class AvroWire {

    /** Avro 명세의 단일 객체 인코딩 마커. */
    public static final byte MARKER_0 = (byte) 0xC3;
    public static final byte MARKER_1 = (byte) 0x01;

    private AvroWire() {
    }

    public static boolean isAvro(byte[] bytes) {
        return bytes != null && bytes.length >= 10 && bytes[0] == MARKER_0 && bytes[1] == MARKER_1;
    }
}
