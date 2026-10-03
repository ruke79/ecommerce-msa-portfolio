package com.project.chat_service.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.chat_service.dto.MessageDto;

/** 상담 스레드 · 메시지 모델의 작은 규칙들(모델 리팩토링 ⑤). */
class SupportThreadModelTest {

    @Test
    @DisplayName("방 id 는 roomId() 가 만드는 모양일 때만 스레드 번호다 — support-+7 · support-007 은 없는 스레드")
    void threadIdOfIsCanonical() {
        assertThat(SupportThread.threadIdOf("support-7")).isEqualTo(7L);
        assertThat(SupportThread.threadIdOf("support-+7")).isEqualTo(-1L);
        assertThat(SupportThread.threadIdOf("support-007")).isEqualTo(-1L);
        assertThat(SupportThread.threadIdOf("support-abc")).isEqualTo(-1L);
        assertThat(SupportThread.threadIdOf("0b7c5d0e-uuid-room")).isNull();
        assertThat(SupportThread.threadIdOf(null)).isNull();
    }

    @Test
    @DisplayName("변경한 컬럼만 UPDATE 한다(@DynamicUpdate) — 메시지 요약 갱신이 동시에 일어난 배정을 덮지 않게")
    void onlyDirtyColumnsAreWritten() {
        assertThat(SupportThread.class.isAnnotationPresent(org.hibernate.annotations.DynamicUpdate.class)).isTrue();
    }

    @Test
    @DisplayName("목록 요약은 이모지(서로게이트 쌍)를 반으로 자르지 않는다")
    void previewDoesNotSplitSurrogatePair() {
        SupportThread t = SupportThread.open(1L, Instant.EPOCH);
        // 199번째 char 가 이모지의 앞 절반이 되도록 — char 로 자르면 짝 잃은 서로게이트가 남는다.
        String text = "a".repeat(SupportThread.PREVIEW_LENGTH - 2) + "😀" + "b".repeat(10);

        t.recordMessage(text, true, Instant.EPOCH);

        String preview = t.getLastMessagePreview();
        assertThat(preview).endsWith("…");
        assertThat(preview.length()).isLessThanOrEqualTo(SupportThread.PREVIEW_LENGTH);
        assertThat(preview.chars().anyMatch(c -> Character.isSurrogate((char) c))).isFalse();
    }

    @Test
    @DisplayName("메시지 시각은 그 순간의 epoch 밀리초다 — 시간대로 다시 해석하지 않는다(예전에는 9시간 일렀다)")
    void messageTimeIsTheInstant() {
        Instant sent = Instant.parse("2026-10-03T03:00:00Z");
        MessageDto dto = MessageDto.builder().roomId("r").content("비밀 상담 내용").build();

        dto.setSendTimeAndSender(sent, "닉네임", 7L, 0);

        assertThat(dto.getCreatedAt()).isEqualTo(sent.toEpochMilli());
    }

    @Test
    @DisplayName("🔒 MessageDto 의 toString 에 대화 본문 · 닉네임이 실리지 않는다")
    void toStringOmitsContent() {
        MessageDto dto = MessageDto.builder().roomId("r").senderId(7L).nickname("닉네임").content("비밀 상담 내용").build();

        assertThat(dto.toString()).doesNotContain("비밀 상담 내용").doesNotContain("닉네임").contains("roomId=r");
    }
}
