package com.project.common.message.dto.request;

import java.io.Serializable;
import java.util.Map;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code outgoing-mail} 토픽의 메시지 — "이 사람에게 이 종류의 메일을 보내라"는 <b>명령</b>이다.
 *
 * <p>도메인 이벤트가 아니라 명령이므로 스키마를 <b>수신자(notification-service)가 소유</b>한다.
 * 다른 토픽들(order, payment)은 발행자가 스키마를 갖고 알림 서비스가 필요한 필드만 꺼내 읽지만
 * (NotificationEventListener 의 JsonNode 방식), 이건 반대다 — 보내는 쪽이 "무엇을 보낼지"를
 * 알림 서비스의 어휘로 말해야 한다.
 *
 * <p><b>본문을 싣지 않는 이유.</b> 제목·HTML 본문을 발행자가 만들어 보내면 메일 문안이 서비스마다
 * 흩어진다. 브랜딩을 바꾸려면 여러 저장소를 고쳐야 하고, 어느 서비스가 어떤 문구를 쓰는지 아무도
 * 모르게 된다. 그래서 <b>종류({@code kind})와 값({@code params})만</b> 싣고 렌더링은
 * notification-service 의 {@code MailTemplates} 한 곳에서 한다. SMS 가 이미 그렇게 되어 있고
 * (SmsTemplates), 상용 트랜잭션 메일 서비스의 템플릿 방식도 같다.
 *
 * <p>⚠️ <b>{@code params} 에는 자격증명이 들어간다.</b> 가입 확인 메일의 {@code confirmationUrl}
 * 은 그 자체로 계정을 활성화할 수 있는 링크다. 따라서
 * <ul>
 *   <li>이 객체를 <b>로그에 찍지 않는다</b> — {@link #toString()} 이 params 를 가린다</li>
 *   <li>토픽 보존 기간을 짧게 잡는다(docs/29 §4)</li>
 *   <li>수신 측은 발송이 끝나는 즉시 저장된 params 를 지운다(MailMessage#clearParams)</li>
 * </ul>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
public class OutgoingMailRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 멱등 키. 같은 값으로 두 번 오면 메일은 한 번만 나간다.
     *
     * <p>Kafka 는 at-least-once 이므로 이게 없으면 재전달마다 확인 메일이 한 통씩 더 간다.
     * 수신 측 {@code mail_message.source_event_id} 가 UNIQUE 라 DB 가 마지막으로 막는다.
     */
    private String sourceEventId;

    /** 수신 주소. */
    private String toEmail;

    /**
     * 수신자 사용자 ID. 알 수 없으면 null 이다.
     *
     * <p>가입 확인 메일은 사용자가 아직 알림 설정을 만들기 전이라 null 일 수 있다. 값이 있으면
     * 수신 측이 설정(수신 거부·언어)을 참조한다.
     */
    private Long recipientUserId;

    /** 메일 종류. notification-service 의 {@code MailKind} 이름과 같아야 한다. */
    private String kind;

    /**
     * 문구에 채워 넣을 값들. 종류마다 필요한 키가 다르다.
     *
     * <p>예: {@code REGISTRATION_CONFIRM → {"confirmationUrl": "https://..."}}
     */
    private Map<String, String> params;

    /**
     * 문구 언어 힌트 (예: {@code ko}, {@code ja}).
     *
     * <p>설정에 지역이 있으면 그쪽이 우선한다. 가입 확인처럼 설정이 아직 없는 경우를 위한 값이다.
     */
    private String locale;

    /**
     * ⚠️ {@code params} 를 그대로 노출하지 않는다. 확인 링크·재설정 링크가 로그로 새면
     * 로그 열람 권한이 곧 계정 탈취 권한이 된다 — 토큰을 DB 에 해시로 저장하는 의미가 사라진다.
     */
    @Override
    public String toString() {
        return "OutgoingMailRequest(sourceEventId=" + sourceEventId
                + ", kind=" + kind
                + ", recipientUserId=" + recipientUserId
                + ", toEmail=" + mask(toEmail)
                + ", paramKeys=" + (params == null ? "[]" : params.keySet())
                + ")";
    }

    /** {@code alice@example.com} → {@code a***@example.com}. */
    private static String mask(String email) {
        if (email == null || email.isBlank()) {
            return "(none)";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
