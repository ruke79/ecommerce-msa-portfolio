package com.project.notification_service.domain;

/**
 * 알림 1건의 SMS 발송 상태.
 *
 * <p>상태를 두는 이유는 <b>중복 발송을 막기 위해서</b>다. Kafka 는 at-least-once 이고
 * 알림 컨슈머도 재전달을 받는다. 상태 없이 "이벤트가 오면 보낸다"로 두면 재전달마다 문자가
 * 한 통씩 더 나간다 — 사용자에게는 스팸이고 우리에게는 요금이다.
 */
public enum SmsStatus {

    /** 이 알림은 SMS 대상이 아니다 (종류·사용자 설정·번호 미등록). */
    NOT_APPLICABLE,

    /** 보내야 하는데 아직 안 보냈다. */
    PENDING,

    /** 공급자가 접수했다. 단말 도달은 DLR 을 받아야 안다. */
    SENT,

    /** 일시적 실패. 다음 시도에서 다시 집는다. */
    RETRYABLE,

    /** 영구 실패 (번호 오류, 잔액 부족, 발신번호 미등록). 사람이 봐야 한다. */
    FAILED
}
