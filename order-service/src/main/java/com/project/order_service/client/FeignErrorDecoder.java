package com.project.order_service.client;


import feign.FeignException;
import feign.Response;
import feign.codec.ErrorDecoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.project.common.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;


/**
 * 다른 서비스가 2xx 가 아닌 응답을 줬을 때 무엇으로 바꿀지 정한다.
 *
 * <h3>🐛 {@code null} 을 돌려주고 있었다</h3>
 *
 * <p>예전에는 400 이면 {@code break} 해서 마지막 {@code return null} 로 떨어지고, 404 도
 * 메서드 이름에 "find" 가 없으면 마찬가지였다. Feign 은 이 반환값을 그대로 던진다:
 *
 * <pre>{@code throw errorDecoder.decode(configKey, response); }</pre>
 *
 * <p>즉 {@code throw null} 이고, JVM 이 그것을 {@code NullPointerException} 으로 바꾼다.
 * <b>원래 무슨 일이 있었는지(어느 서비스가 몇 번으로 답했는지)는 그 순간 사라진다.</b>
 * 호출부에서는 "어딘가에서 NPE" 로만 보인다.
 *
 * <p>{@code default} 갈래도 나빴다 — {@code new Exception(...)} 은 <b>검사 예외</b>라
 * Feign 이 {@code RuntimeException} 으로 감싸 버려 역시 원인이 한 겹 묻혔다.
 *
 * <p>이제 모르는 상태는 {@link FeignException} 그대로 돌려준다. 상태 코드·요청·응답 본문이
 * 메시지에 들어 있어 로그만 보고도 어느 쪽이 무엇을 거절했는지 알 수 있다.
 */
@Slf4j
@Component
public class FeignErrorDecoder implements ErrorDecoder {

    private final ErrorDecoder.Default fallback = new ErrorDecoder.Default();

    Environment env;

    @Autowired
    public FeignErrorDecoder(Environment env) {
        this.env = env;
    }

    @Override
    public Exception decode(String methodKey, Response response) {
        // 이메일·토큰이 섞일 수 있는 URL 은 남기지 않는다. 메서드와 상태면 충분하다.
        log.warn("서비스 간 호출 실패: {} -> {}", methodKey, response.status());

        if (response.status() == 404 && methodKey.contains("find")) {
            return new com.project.common.exception.CustomException(ErrorCode.USER_NOT_FOUND);
        }

        // 나머지는 Feign 기본 동작 — 상태·요청·응답 본문이 담긴 FeignException 이다.
        // ⚠️ null 을 돌려주면 Feign 이 `throw null` 을 해서 원인이 통째로 사라진다.
        return fallback.decode(methodKey, response);
    }
}
