package com.project.chat_service.common.exception.handler;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    /* 404 NOT_FOUND : Resource 를 찾을 수 없음 */
    USER_NOT_FOUND(NOT_FOUND, "해당 회원 정보를 찾을 수 없습니다.", "015"),

    NOTIFICATION_NOT_FOUND(NOT_FOUND, "해당 알림을 찾을 수 없습니다." , "020"),
    UNAUTHORIZED_TOKEN_EXCEPTION(UNAUTHORIZED, "다시 채팅을 시작해주세요.", "028"),

    /* 고객센터 채팅(docs/58). 남의 상담은 403 이 아니라 404 — 있는지조차 알려 주지 않는다. */
    SUPPORT_THREAD_NOT_FOUND(NOT_FOUND, "상담을 찾을 수 없습니다.", "040"),
    SUPPORT_AGENT_ONLY(FORBIDDEN, "상담원만 쓸 수 있습니다.", "041"),
    SUPPORT_THREAD_CLOSED(CONFLICT, "종료된 상담입니다. 새로 문의해 주세요.", "042"),
    SUPPORT_ALREADY_CLAIMED(CONFLICT, "다른 상담원이 이미 맡은 상담입니다.", "043"),
    SUPPORT_NOT_ASSIGNED(CONFLICT, "먼저 상담을 맡아 주세요.", "044"),
    SUPPORT_EMPTY_MESSAGE(BAD_REQUEST, "메시지를 입력해 주세요.", "045"),
    /* 상담 개편(docs/58 §11) — 평가 · 메모 · 반납 */
    SUPPORT_NOT_CLOSED(CONFLICT, "종료된 상담만 평가할 수 있습니다.", "046"),
    SUPPORT_ALREADY_RATED(CONFLICT, "이미 평가한 상담입니다.", "047"),
    SUPPORT_INVALID_RATING(BAD_REQUEST, "평가는 1~5점, 의견은 500자까지 남길 수 있습니다.", "048"),
    SUPPORT_NOTE_TOO_LONG(BAD_REQUEST, "메모는 2000자까지 남길 수 있습니다.", "049"),
    SUPPORT_NOT_YOURS(CONFLICT, "내가 맡은 상담만 반납할 수 있습니다.", "050");

    private final HttpStatus httpStatus;
    private final String detail;
    private final String errorCode;

    public static CustomException throwNotificationNotFound() {
        throw new CustomException(NOTIFICATION_NOT_FOUND);
    }

    public static CustomException throwUserrNotFound() {
        throw new CustomException(USER_NOT_FOUND);
    }
    public static CustomException unauthorizedTokenException() {
        throw new CustomException(UNAUTHORIZED_TOKEN_EXCEPTION);
    }

}
