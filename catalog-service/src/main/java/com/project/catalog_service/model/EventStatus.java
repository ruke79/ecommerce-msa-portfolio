package com.project.catalog_service.model;

/**
 * 기획전의 상태.
 *
 * <p><b>왜 {@code published} 불리언 하나로 두지 않았나.</b> 배너·공지는 그렇게 해도 됐다 —
 * "지금 보이는가"만 알면 되기 때문이다. 기획전은 다르다. 할인이 걸려 있어서 <b>언제
 * 시작했고 언제 끝났는지</b>가 사후에도 의미를 갖는다. 끝난 기획전과 아직 시작하지 않은
 * 기획전을 같은 "published=false" 로 묶으면, 관리자 목록에서 둘을 구분할 수 없고 "왜 안
 * 보이지?"를 매번 시작·종료 시각을 읽어 추론해야 한다.
 *
 * <p>상태는 스케줄러가 시각에 맞춰 넘긴다({@code EventScheduler}). 사람이 자정에 맞춰
 * 버튼을 누르는 방식은 반드시 놓친다.
 */
public enum EventStatus {

    /**
     * 작성 중. 아무 데도 보이지 않고 할인도 걸리지 않는다.
     *
     * <p>시작 시각이 이미 지났어도 DRAFT 면 시작되지 않는다 — 준비가 안 된 기획전이
     * 저장하자마자 나가는 것을 막는다.
     */
    DRAFT,

    /** 확정됐고 시작 시각을 기다린다. 스케줄러가 {@link #ACTIVE} 로 넘긴다. */
    SCHEDULED,

    /** 진행 중. 이 상태일 때만 할인이 상품 가격에 합성된다. */
    ACTIVE,

    /** 끝났다. 할인은 즉시 사라지고 목록에는 기록으로 남는다. */
    ENDED;

    /** 지금 할인이 적용되는 상태인가. */
    public boolean isActive() {
        return this == ACTIVE;
    }

    /** 사용자에게 보이는 상태인가(진행 중이거나 끝난 것 — 초안과 예약분은 감춘다). */
    public boolean isPublic() {
        return this == ACTIVE;
    }
}
