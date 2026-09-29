package com.project.chat_service.common.exception.handler;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 🐛 getter 가 없었다 — Jackson 은 private 필드를 읽지 못해 에러 응답이 직렬화에 실패했다("No serializer found
 * for class ApiResponse"). 화면은 문구 대신 빈 응답이나 다른 500 을 받았다. {@code @Getter} 로 내보낸다(docs/58).
 */
@Getter
@RequiredArgsConstructor
public class ApiResponse
{

    private final ApiStatus status;
    private final String message;
    private final String errorCodeName;
    private final Object data;

    public static ApiResponse success(Object data) {
        return new ApiResponse(ApiStatus.SUCCESS, null, null, data);
    }
    public static ApiResponse error(String message, String errorCodeName) {
        return new ApiResponse(ApiStatus.ERROR, message, errorCodeName, null);
    }
}
