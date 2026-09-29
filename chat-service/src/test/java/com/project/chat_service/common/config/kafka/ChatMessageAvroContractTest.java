package com.project.chat_service.common.config.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.kafka.avro.AvroMappingContract;

/**
 * chat 토픽의 DTO({@code MessageDto}) ↔ Avro 매핑 계약. 매핑이 chat-service 에 있어 common 의
 * {@code AvroMappingContractTest} 가 보지 못한다 — 같은 검사를 여기서 한다(docs/55 §7-7).
 */
@DisplayName("chat — DTO ↔ Avro 매핑 계약")
class ChatMessageAvroContractTest {

    @Test
    @DisplayName("🔴 MessageDto 의 필드를 전부 싣고, 왕복해도 같은 값이 돌아온다")
    void chatMappingKeepsEveryField() {
        assertThat(AvroMappingContract.verify(new ChatMessageAvroMapping(), Map.of())).isEmpty();
    }
}
