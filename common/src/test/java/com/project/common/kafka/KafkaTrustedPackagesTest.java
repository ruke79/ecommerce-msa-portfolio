package com.project.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import com.project.common.message.dto.request.CartRollbackRequest;

/**
 * B-115: 헤더(__TypeId__)가 메시지 DTO 패키지 밖의 클래스를 가리키면 거절한다 — 예전 {@code "*"} 는 그 클래스를 만들었다.
 * [JA] ヘッダーが DTO パッケージ外のクラスを指すと拒否する — 以前の "*" はそのクラスを作った。
 */
class KafkaTrustedPackagesTest {

    private static byte[] json(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static RecordHeaders typeHeader(String className) {
        RecordHeaders headers = new RecordHeaders();
        headers.add("__TypeId__", className.getBytes(StandardCharsets.UTF_8));
        return headers;
    }

    @Test
    @DisplayName("B-115: 헤더가 DTO 패키지 밖의 클래스를 가리키면 역직렬화하지 않는다")
    void rejectsTypeHeaderOutsideMessageDtos() {
        JsonDeserializer<CartRollbackRequest> d = KafkaTrustedPackages.trustMessageDtos(
                new JsonDeserializer<>(CartRollbackRequest.class, true));
        assertThatThrownBy(() -> d.deserialize("cart-rollback",
                typeHeader("com.project.common.kafkatest.NotAMessage"), json("{\"value\":\"x\"}")))
                .hasMessageContaining("not in the trusted packages");
    }

    @Test
    @DisplayName("B-115: DTO 패키지 안의 헤더 · 헤더 없음은 그대로 읽는다")
    void readsMessageDtos() {
        JsonDeserializer<CartRollbackRequest> d = KafkaTrustedPackages.trustMessageDtos(
                new JsonDeserializer<>(CartRollbackRequest.class, true));
        assertThat(d.deserialize("cart-rollback", typeHeader(CartRollbackRequest.class.getName()), json("{}")))
                .isInstanceOf(CartRollbackRequest.class);
        assertThat(d.deserialize("cart-rollback", new RecordHeaders(), json("{}")))
                .isInstanceOf(CartRollbackRequest.class);
    }
}
