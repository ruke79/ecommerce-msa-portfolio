package com.project.common.kafka.avro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.message.dto.request.CartRollbackRequest;

/**
 * Avro 역직렬화기 — 디코더(스키마 이력 읽기 · 파싱 포함)를 레코드마다가 아니라 한 번만 만든다.
 *
 * <p>[JA] Avro デシリアライザ — デコーダー(スキーマ履歴の読み込み・解析を含む)をレコードごとではなく一度だけ作る。
 */
@DisplayName("AvroDtoDeserializer — 디코더는 한 번")
class AvroDtoDeserializerTest {

    @Test
    @DisplayName("🔴 레코드 100 건을 읽어도 mapping.decoder() 는 한 번 — 예전에는 레코드마다 avsc 이력을 읽고 파싱했다")
    void decoderIsBuiltOnce() {
        CartRollbackRequestAvroMapping mapping = spy(new CartRollbackRequestAvroMapping());
        byte[] wire;
        try (AvroDtoSerializer<CartRollbackRequest> serializer = new AvroDtoSerializer<>(new CartRollbackRequestAvroMapping())) {
            wire = serializer.serialize("cart-rollback", new CartRollbackRequest(7L, 2));
        }
        try (AvroDtoDeserializer<CartRollbackRequest> deserializer = new AvroDtoDeserializer<>(mapping)) {
            for (int i = 0; i < 100; i++) {
                assertThat(deserializer.deserialize("cart-rollback", wire)).isNotNull();
            }
        }
        verify(mapping, times(1)).decoder();
    }
}
