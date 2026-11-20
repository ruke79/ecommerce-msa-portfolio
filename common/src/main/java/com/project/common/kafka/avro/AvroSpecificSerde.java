package com.project.common.kafka.avro;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.message.BinaryMessageEncoder;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

/**
 * 생성된 {@link SpecificRecord} 를 그대로 싣는 Kafka Streams 용 {@link Serde} — 단일 객체 인코딩.
 *
 * <p>{@link AvroDtoSerializer} 계열은 DTO↔레코드 변환을 품는다(리스너가 DTO 를 계속 받도록). 반면
 * Streams 토폴로지의 <b>상태</b>(변경 로그·리파티션 토픽·상태 저장소·프로젝션 출력)는 도메인 DTO 가
 * 아니라 스키마가 진실이므로 레코드를 바로 싣는다. 형식은 같다: {@code C3 01} + 지문 + 본문, 그래서
 * 문자열 소비자의 {@link AvroJsonTextDeserializer} 나 다른 서비스의 하이브리드 역직렬화기가 출력
 * 토픽을 그대로 읽을 수 있다(docs/38 §1.16·§1.17).
 *
 * @param <R> 생성 클래스. {@code R.getEncoder()} / {@code R.getDecoder()} 를 넘긴다.
 *
 * <p>[JA] 生成された {@link SpecificRecord} をそのまま載せる Kafka Streams 用の {@link Serde} — 単一オブジェクトエンコーディング。
 * {@link AvroDtoSerializer} 系は DTO↔レコード変換を抱える(リスナーが DTO を受け取り続けるように)。一方 Streams トポロジーの<b>状態</b>(変更ログ · リパーティション
 * トピック · 状態ストア · プロジェクション出力)はドメイン DTO ではなくスキーマが真実なので、レコードを直接載せる。形式は同じ: {@code C3 01} + フィンガープリント + 本文、
 * だから文字列コンシューマーの {@link AvroJsonTextDeserializer} や他サービスのハイブリッドデシリアライザーが出力トピックをそのまま読める(docs/38 §1.16 · §1.17)。
 * R は生成クラス。{@code R.getEncoder()} / {@code R.getDecoder()} を渡す。
 */
public final class AvroSpecificSerde<R extends SpecificRecord> implements Serde<R> {

    private final BinaryMessageEncoder<R> encoder;
    private final BinaryMessageDecoder<R> decoder;

    public AvroSpecificSerde(BinaryMessageEncoder<R> encoder, BinaryMessageDecoder<R> decoder) {
        this.encoder = encoder;
        this.decoder = decoder;
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        // 설정할 것이 없다 — 스키마가 코드에 있다.
        // [JA] 設定するものはない — スキーマがコードにある。
    }

    @Override
    public Serializer<R> serializer() {
        return (topic, record) -> {
            if (record == null) {
                return null;
            }
            try {
                ByteBuffer buffer = encoder.encode(record);
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                return bytes;
            } catch (IOException e) {
                throw new SerializationException("Avro 직렬화 실패: " + record.getSchema().getName() + " → " + topic, e);
            }
        };
    }

    @Override
    public Deserializer<R> deserializer() {
        return (topic, bytes) -> {
            if (bytes == null) {
                return null;
            }
            try {
                return decoder.decode(ByteBuffer.wrap(bytes));
            } catch (IOException e) {
                throw new SerializationException("Avro 역직렬화 실패: " + topic, e);
            }
        };
    }
}
