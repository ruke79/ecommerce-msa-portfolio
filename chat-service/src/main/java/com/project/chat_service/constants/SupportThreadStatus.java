package com.project.chat_service.constants;

/** 상담 스레드의 상태 (docs/58). V7 의 CHECK 와 같은 값이다. */
public enum SupportThreadStatus {
    /** 고객이 문의를 열었고 아직 아무도 맡지 않았다. */
    WAITING,
    /** 상담원이 맡았다. */
    ACTIVE,
    /** 끝났다. 다시 문의하면 새 스레드가 열린다. */
    CLOSED
}
