package com.project.chat_service.dto;

import java.time.Instant;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.project.chat_service.constants.SupportThreadStatus;
import com.project.chat_service.constants.SupportTopic;

import lombok.Builder;
import lombok.Getter;

/**
 * 상담 스레드 — 고객 화면과 상담 데스크가 같이 쓴다 (docs/58).
 *
 * <p>id 들은 <b>문자열</b>로 나간다 — 회원 id 는 TSID(19자리)라 JS {@code Number} 에 담기면 끝자리가 뭉개진다
 * (MessageDto.senderId 와 같은 이유).
 *
 * <p>고객에게는 상담원의 <b>표시 이름</b>만 간다(닉네임). 상담원의 회원 id 는 고객 화면에 필요 없지만, 메시지가
 * "내 것인가" 를 가르는 데 senderId 를 쓰므로 숨길 수 없다 — id 는 식별자일 뿐 연락처가 아니다.
 */
@Getter
@Builder
public class SupportThreadDto {

    @JsonSerialize(using = ToStringSerializer.class)
    private final Long threadId;
    /** STOMP 구독·메시지의 방 id — {@code support-<threadId>}. */
    private final String roomId;
    private final SupportThreadStatus status;

    @JsonSerialize(using = ToStringSerializer.class)
    private final Long customerId;
    /** 상담 데스크에만 채운다 — 고객 화면에는 자기 이름이 필요 없다. */
    private final String customerName;

    @JsonSerialize(using = ToStringSerializer.class)
    private final Long agentId;
    private final String agentName;

    private final boolean awaitingReply;
    private final String lastMessagePreview;
    private final Instant lastMessageAt;
    private final Instant createdAt;
    private final Instant assignedAt;
    private final Instant closedAt;

    /** 문의 유형(docs/58 §11). */
    private final SupportTopic topic;
    /** 대기 순번(1 = 다음 차례) — 대기 중인 고객 화면에만. 그 밖에는 null. */
    private final Long queuePosition;

    /** 만족도(1~5)와 의견 — 평가 전이면 null. 고객은 자기 것을, 데스크는 모두 본다. */
    private final Integer rating;
    private final String ratingComment;
    private final Instant ratedAt;

    /** 🔒 상담원 내부 메모 — 데스크 응답에만. 고객 응답에서는 언제나 null(JSON 에서도 빠진다). */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private final String internalNote;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private final Instant noteUpdatedAt;
    /** 데스크의 상담 하나를 볼 때만 — 이 고객의 지난(종료) 상담 수. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private final Long customerPastThreads;
}
