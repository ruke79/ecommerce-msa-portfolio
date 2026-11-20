package com.project.chat_service.constants;

/** 상담 스레드의 상태 (docs/58). V7 의 CHECK 와 같은 값이다. */
// [JA] 相談スレッドの状態(docs/58)。V7 の CHECK と同じ値である。
public enum SupportThreadStatus {
    /** 고객이 문의를 열었고 아직 아무도 맡지 않았다. */
    // [JA] 顧客が問い合わせを開き、まだ誰も担当していない。
    WAITING,
    /** 상담원이 맡았다. */
    // [JA] 相談員が担当した。
    ACTIVE,
    /** 끝났다. 다시 문의하면 새 스레드가 열린다. */
    // [JA] 終了した。再び問い合わせると新しいスレッドが開く。
    CLOSED
}
