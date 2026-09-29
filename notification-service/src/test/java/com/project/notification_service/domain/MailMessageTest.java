package com.project.notification_service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 메일 아웃박스 행의 상태 전이.
 *
 * <p>여기서 가장 중요한 것은 <b>{@code params} 가 종료 상태에서 지워지는가</b>이다. 그 안에는
 * 가입 확인 링크·비밀번호 재설정 링크가 들어 있고, 그건 그 자체로 계정을 활성화하거나 비밀번호를
 * 바꿀 수 있는 자격증명이다. user-service 가 토큰을 SHA-256 해시로만 저장하는데 여기 원문
 * 링크가 남아 있으면 그 방어가 통째로 무의미해진다.
 */
@DisplayName("메일 아웃박스")
class MailMessageTest {

    private MailMessage newMessage() {
        return MailMessage.builder()
                .toEmail("alice@example.com")
                .recipientUserId(7L)
                .kind(MailKind.REGISTRATION_CONFIRM)
                .params("{\"confirmationUrl\":\"https://shop.example.com/confirm?token=SECRET\"}")
                .locale("ko")
                .sourceEventId("registration-confirm:7:1")
                .build();
    }

    @Test
    @DisplayName("새 행은 PENDING 이고 시도 횟수가 0 이다")
    void 초기_상태() {
        MailMessage m = newMessage();
        assertThat(m.getStatus()).isEqualTo(MailStatus.PENDING);
        assertThat(m.getAttempts()).isZero();
        assertThat(m.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("🔒 발송에 성공하면 링크를 지운다 — 보관 기간을 '발송까지'로 좁힌다")
    void 성공하면_링크_삭제() {
        MailMessage m = newMessage();
        assertThat(m.rawParams()).contains("SECRET");

        m.recordAttempt(MailStatus.SENT, null);

        assertThat(m.getStatus()).isEqualTo(MailStatus.SENT);
        assertThat(m.rawParams()).isNull();
        assertThat(m.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("🔒 영구 실패로 끝나도 링크를 지운다")
    void 영구실패해도_링크_삭제() {
        MailMessage m = newMessage();
        m.recordAttempt(MailStatus.FAILED, "invalid_address");

        assertThat(m.rawParams()).isNull();
        assertThat(m.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("재시도 중에는 링크를 남긴다 — 지우면 다음 시도에서 보낼 내용이 없다")
    void 재시도중에는_링크_유지() {
        MailMessage m = newMessage();
        m.recordAttempt(MailStatus.RETRYABLE, "smtp 421");

        assertThat(m.getStatus()).isEqualTo(MailStatus.RETRYABLE);
        assertThat(m.rawParams()).contains("SECRET");
        assertThat(m.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("시도 횟수는 성공·실패 무관하게 오른다")
    void 시도_횟수() {
        MailMessage m = newMessage();
        m.recordAttempt(MailStatus.RETRYABLE, "a");
        m.recordAttempt(MailStatus.RETRYABLE, "b");
        assertThat(m.getAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("상한에 닿으면 더 시도하지 않는다")
    void 재시도_상한() {
        MailMessage m = newMessage();
        assertThat(m.canRetry(3)).isTrue();

        m.recordAttempt(MailStatus.RETRYABLE, "1");
        m.recordAttempt(MailStatus.RETRYABLE, "2");
        assertThat(m.canRetry(3)).isTrue();

        m.recordAttempt(MailStatus.RETRYABLE, "3");
        assertThat(m.canRetry(3)).isFalse();
    }

    @Test
    @DisplayName("실패 사유가 길어도 컬럼 길이(300)를 넘지 않는다 — 넘으면 INSERT 자체가 실패한다")
    void 사유_길이_제한() {
        MailMessage m = newMessage();
        m.recordAttempt(MailStatus.FAILED, "x".repeat(1000));
        assertThat(m.getDetail()).hasSize(300);
    }

    @Test
    @DisplayName("🔒 toString 에 링크도 전체 주소도 들어가지 않는다")
    void toString_에_비밀_없음() {
        MailMessage m = newMessage();
        String s = m.toString();

        assertThat(s).doesNotContain("SECRET");
        assertThat(s).doesNotContain("confirmationUrl");
        assertThat(s).doesNotContain("alice@");
        assertThat(s).contains("a***@example.com");
        assertThat(s).contains("REGISTRATION_CONFIRM");
    }
}
