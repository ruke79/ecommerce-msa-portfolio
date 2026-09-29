package com.project.chat_service.service;

import java.time.Instant;

import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 상담 데스크 실시간 알림 (docs/58 §9).
 *
 * <p>상담원이 구독하는 {@value #DESTINATION} 로 "무엇이 바뀌었다" 만 보낸다 — 스레드 번호와 종류뿐, <b>본문은 없다.</b>
 * 화면은 이것을 받고 목록을 다시 읽는다. 본문을 실으면 이 주소를 구독할 수 있는 사람이 곧 모든 상담의 대화를
 * 받는 사람이 되는데, 그 판정을 두 곳(REST 와 여기)에서 맞춰야 한다. 번호만 보내면 판정은 REST 한 곳이다.
 *
 * <p>구독은 상담원·관리자만 된다(StompHandler). chat-service 는 파드 하나다(k8s 매니페스트의 replicas: 1 — 인메모리
 * 브로커). 여럿이 되면 친구 채팅과 같은 이유로 외부 브로커(릴레이)가 필요하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupportDeskNotifier {

    public static final String DESTINATION = "/sub/support/desk";

    /** 알림의 종류. */
    public enum Kind {
        /** 고객이 새로 문의했다(대기 목록에 하나 늘었다). */
        NEW_THREAD,
        /** 상담에 메시지가 왔다. */
        MESSAGE,
        /** 누가 맡았다(대기 목록에서 빠졌다). */
        CLAIMED,
        /** 끝났다. */
        CLOSED,
        /** 상담원이 반납했다(대기 목록에 다시 올라왔다). */
        RELEASED
    }

    /** 데스크로 가는 알림 — 번호와 종류뿐. id 들은 문자열(TSID 가 JS Number 에서 뭉개지지 않게). */
    public record DeskEvent(
            @JsonSerialize(using = ToStringSerializer.class) Long threadId,
            Kind kind,
            @JsonSerialize(using = ToStringSerializer.class) Long senderId,
            Instant at) {
    }

    private final SimpMessageSendingOperations template;

    public void notify(Long threadId, Kind kind, Long senderId) {
        if (threadId == null) {
            return;
        }
        try {
            template.convertAndSend(DESTINATION, new DeskEvent(threadId, kind, senderId, Instant.now()));
        } catch (RuntimeException e) {
            // 알림은 부가 기능이다 — 실패해도 상담 자체(저장·배정·종료)를 되돌리지 않는다. 데스크는 주기적으로도 다시 읽는다.
            log.warn("상담 데스크 알림 실패: threadId={} kind={} ({})", threadId, kind, e.toString());
        }
    }
}
