package com.project.chat_service.common.exception.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.project.common.domain.exception.DomainException;
import com.project.common.exception.CustomRestExceptionHandler;

/**
 * chat 의 예외 응답 — 처리기가 둘(chat 의 {@link ExceptionResponseHandler} · common 의 {@link CustomRestExceptionHandler})이다.
 *
 * <p>chat 은 com.project.common 도 스캔해 둘 다 등록된다. 예전에는 둘 다 순서가 없고 둘 다 {@code Exception} 전체를 받아 등록
 * 순서가 결과를 정했다 — chat 이 먼저면 잘못된 요청(타입 불일치 등)이 500, common 이 먼저면 chat 의 응답 모양이 바뀌었다. 여기서는
 * 일부러 <b>common 을 먼저</b> 넣는다 — {@code @Order} 가 이겨야 한다.
 *
 * <p>사람에게 쓴 문구의 도메인 예외는 400 과 그 문구(docs/57 §15). 운영자용 IllegalStateException 은 500 일반 문구.
 *
 * <p>[JA] chat の例外応答 — 処理器が二つある。わざと common を先に入れる — {@code @Order} が勝たなければならない。
 */
@DisplayName("chat 예외 응답 — 두 처리기의 순서 · 도메인 예외")
class DomainExceptionResponseTest {

    @RestController
    static class Throwing {
        @GetMapping("/domain")
        String domain() {
            throw new DomainException("채팅 회원 정보가 없습니다. 다시 로그인한 뒤 시도해 주세요.");
        }

        @GetMapping("/lock")
        String lock() {
            throw new IllegalStateException("Failed to acquire lock for chatroom creation.");
        }

        @GetMapping("/typed")
        String typed(@RequestParam int size) {
            return "ok " + size;
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Throwing())
            .setControllerAdvice(new CustomRestExceptionHandler(), new ExceptionResponseHandler())   // common 이 먼저
            .build();

    @Test
    @DisplayName("🔴 도메인 예외는 chat 의 처리기가 받는다 — 400 + 사람 문구 + chat 응답 모양(errorCodeName)")
    void domainIs400WithMessage() throws Exception {
        mvc.perform(get("/domain"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("채팅 회원 정보가 없습니다. 다시 로그인한 뒤 시도해 주세요."))
                .andExpect(jsonPath("$.errorCodeName").value("002"));
    }

    @Test
    @DisplayName("🔴 잘못된 요청(타입 불일치)은 500 이 아니라 400 — chat 이 모르는 예외는 common 이 맡는다")
    void badRequestIsNot500() throws Exception {
        mvc.perform(get("/typed").param("size", "abc")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("🔒 운영자용 IllegalStateException 은 500 일반 문구 — 문구를 내보내지 않는다")
    void illegalStateIsGeneric() throws Exception {
        String body = mvc.perform(get("/lock")).andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("acquire lock");
    }
}
