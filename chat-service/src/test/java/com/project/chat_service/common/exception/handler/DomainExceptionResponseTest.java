package com.project.chat_service.common.exception.handler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.project.common.domain.exception.DomainException;

/**
 * 사람에게 쓴 문구의 도메인 예외는 400 과 그 문구로 — 예전에는 같은 문구를 IllegalStateException 으로 던져 500 "서버 오류" 가
 * 됐다(docs/57 §15). 운영자용 IllegalStateException 은 여전히 500 일반 문구다.
 */
@DisplayName("chat 예외 응답 — 도메인 예외")
class DomainExceptionResponseTest {

    private final ExceptionResponseHandler handler = new ExceptionResponseHandler();

    @Test
    @DisplayName("도메인 예외는 400 + 사람 문구")
    void domainIs400WithMessage() {
        var res = handler.handleDomainException(new DomainException("채팅 회원 정보가 없습니다. 다시 로그인한 뒤 시도해 주세요."));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getMessage()).contains("채팅 회원 정보가 없습니다");
    }

    @Test
    @DisplayName("🔒 운영자용 IllegalStateException 은 500 일반 문구 — 문구를 내보내지 않는다")
    void illegalStateIsGeneric() {
        var res = handler.handleUnexpectedException(new IllegalStateException("Failed to acquire lock for chatroom creation."));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody().getMessage()).doesNotContain("acquire lock");
    }
}
