package com.project.delivery_service.domain;

import java.time.Duration;

/**
 * 배송 상태 — <b>내부 표준</b>. 택배사 문자열을 그대로 쓰지 않는다(docs/25 §3.2).
 *
 * <p>택배사마다 상태 표현이 다르다. 우체국은 {@code 접수/발송/도착/배달준비/배달완료},
 * CJ는 {@code 집화처리/간선상차/간선하차/배달출발/배달완료} 식이다. 그대로 저장하면
 * 화면과 로직이 택배사마다 갈라지고, 택배사를 하나 추가할 때마다 둘 다 고쳐야 한다.
 *
 * <p>각 상태가 스스로 두 가지를 안다:
 * <ul>
 *   <li><b>다음 폴링까지의 간격</b> — 전 건을 같은 주기로 조회하면 공공 API 일일 한도를
 *       금방 넘긴다. 끝난 배송을 계속 조회하는 것이 쿼터 낭비의 주범이다.</li>
 *   <li><b>고객에게 보여줄 단계</b> — 상태를 그대로 노출하면 사용자는 이해하지 못한다.
 *       아마존처럼 소수의 단계로 접는다.</li>
 * </ul>
 *
 * <p><b>순서가 곧 진행도다.</b> {@link #isProgressFrom} 이 {@code ordinal()} 을 쓰므로
 * 상수 선언 순서를 바꾸면 의미가 바뀐다. 새 상태는 흐름상 맞는 자리에 넣어야 한다.
 */
public enum DeliveryStatus {

    /** 배송 건은 만들어졌지만 송장번호가 없다. 조회할 대상 자체가 없다. */
    PENDING(null, Stage.ORDERED, false),

    /**
     * 택배사가 접수 정보만 받았다 — 실물은 아직 판매자에게 있다.
     *
     * <p>🐛 단계가 {@code ORDERED} 였다. 송장을 등록하면 주문은 {@code DISPATCHED} 가 되고
     * (F-2) "발송되었습니다" 알림까지 나가는데, 배송 추적의 진행 바는 여전히 <b>주문 완료</b>에
     * 서 있었다 — 같은 화면에서 주문 상태와 진행 바가 서로 다른 말을 했다. 송장 등록이 곧
     * 발송 처리라는 것이 이 시스템의 정의이므로 여기도 {@code SHIPPED} 다.
     */
    INFO_RECEIVED(Duration.ofHours(6), Stage.SHIPPED, false),

    /** 집화 완료. 실물이 택배사 손에 들어갔다. */
    PICKED_UP(Duration.ofHours(3), Stage.SHIPPED, false),

    /** 간선 이동 중. 하루 몇 번 스캔된다. */
    IN_TRANSIT(Duration.ofHours(3), Stage.IN_TRANSIT, false),

    /**
     * 통관 보류 — <b>국제 배송 전용</b>.
     *
     * <p>국내 배송에는 없는 단계이고, 사용자가 <b>직접 조치해야 하는 경우가 있다</b>
     * (개인통관고유부호 제출, 관세 납부). 그래서 알림 대상이고, 진행 바에서도 별도로
     * 보여준다. 이걸 IN_TRANSIT 에 뭉뚱그리면 사용자는 왜 며칠째 멈춰 있는지 알 수 없다.
     */
    CUSTOMS_HOLD(Duration.ofHours(6), Stage.CUSTOMS, false),

    /** 통관 완료. 국내 배송망으로 넘어간다. */
    CUSTOMS_CLEARED(Duration.ofHours(3), Stage.CUSTOMS, false),

    /**
     * 배달 출발 — <b>오늘 도착한다.</b>
     *
     * <p>별도 상태로 둔 이유는 알림 가치가 가장 높은 순간이기 때문이다. 아마존이
     * "Out for delivery" 를 강조하는 이유와 같다. 폴링도 가장 자주 한다.
     */
    OUT_FOR_DELIVERY(Duration.ofMinutes(30), Stage.IN_TRANSIT, false),

    /** 부재 등으로 배달 시도 실패. 택배사가 다시 시도한다. */
    ATTEMPT_FAILED(Duration.ofHours(3), Stage.IN_TRANSIT, false),

    /** 배달 완료. 폴링을 멈춘다. */
    DELIVERED(null, Stage.DELIVERED, true),

    /** 파손·분실·주소불명 등 사람이 봐야 하는 상태. */
    EXCEPTION(Duration.ofHours(12), Stage.EXCEPTION, false),

    /** 반송 완료. 폴링을 멈춘다. */
    RETURNED(null, Stage.EXCEPTION, true),

    /**
     * 발송 전 취소됨.
     *
     * <p>발송 이후에는 이 상태로 갈 수 없다 — 이미 움직이는 물건을 "취소"로 표시하면
     * 실물과 기록이 어긋난다. 그 경우는 반품(회수) 절차이고 {@link #RETURNED} 로 끝난다.
     */
    CANCELLED(null, Stage.CANCELLED, true);

    /** 고객 화면의 진행 단계. 내부 상태를 소수의 단계로 접는다. */
    public enum Stage { ORDERED, SHIPPED, IN_TRANSIT, CUSTOMS, DELIVERED, EXCEPTION, CANCELLED }

    private final Duration pollInterval;
    private final Stage stage;
    private final boolean terminal;

    DeliveryStatus(Duration pollInterval, Stage stage, boolean terminal) {
        this.pollInterval = pollInterval;
        this.stage = stage;
        this.terminal = terminal;
    }

    /** 다음 폴링까지의 간격. {@code null} 이면 폴링하지 않는다. */
    public Duration pollInterval() {
        return pollInterval;
    }

    public Stage stage() {
        return stage;
    }

    /** 더 이상 변하지 않는 상태인가. 폴링을 멈추는 기준이다. */
    public boolean isTerminal() {
        return terminal;
    }

    /**
     * 아직 발송되지 않았는가 — <b>주소 변경과 취소가 가능한 구간</b>.
     *
     * <p>집화(PICKED_UP)가 되는 순간 실물이 택배사 손에 있으므로 우리가 목적지를 바꿀 수
     * 없다. 이 경계를 코드로 못박아 두지 않으면 "취소했는데 물건이 왔다"가 생긴다.
     */
    public boolean isBeforeDispatch() {
        return this == PENDING || this == INFO_RECEIVED;
    }

    /**
     * 고객에게 SMS 를 보낼 만한 상태인가.
     *
     * <p>모든 전이에 알리면 한 건의 배송에 대여섯 번이 나간다 — 소음이다. 실제로 사람이
     * 알고 싶어 하거나 행동이 달라지는 순간만 고른다: <b>출발했다</b>, 오늘 받는다,
     * 도착했다, 문제가 생겼다, 그리고 <b>통관 보류</b>(사용자가 서류를 내야 풀린다).
     *
     * <p>{@link #INFO_RECEIVED}(송장 등록 = 발송)는 나중에 더했다. "상품이 발송되었습니다" 는
     * 대부분의 쇼핑몰이 보내는 알림이고, 받는 사람이 가장 먼저 기다리는 소식이다. 빼 두었던
     * 이유는 문자 요금이었는데, 배송 알림({@code NotificationType.SHIPPING_UPDATED})은
     * 애초에 {@code smsWorthy = false} 라 문자로 나가지 않는다 — 앱 알림과 메일뿐이다.
     * 그래서 요금 문제가 아니었다.
     *
     * <p>여기에 없는 것들(집화·간선 이동·통관 완료·배달 재시도)은 알아도 할 일이 없고,
     * 그 이력은 주문 상세의 배송 추적이 이미 보여준다.
     */
    public boolean isWorthNotifying() {
        return this == INFO_RECEIVED || this == OUT_FOR_DELIVERY || this == DELIVERED
                || this == EXCEPTION || this == RETURNED || this == CUSTOMS_HOLD || this == CANCELLED;
    }

    /**
     * 이 상태가 {@code other} 보다 뒤 단계인가.
     *
     * <p>택배사 이력은 순서가 뒤바뀌어 오는 경우가 있다(스캔 시각과 등록 시각이 다르다).
     * 뒤로 가는 전이를 그대로 반영하면 "배달완료" 였던 배송이 "배송중" 으로 되돌아간다.
     * 진행은 앞으로만 한다 — 단, 예외·반송은 언제든 될 수 있다(실제로 사고가 났으므로).
     * 취소는 발송 전에만 가능하므로 여기서 특별 취급하지 않는다.
     *
     * <p>순서만으로는 안 되는 두 갈래를 따로 둔다(🐛 예전에는 {@code ordinal} 비교뿐이었다):
     * <ul>
     *   <li><b>예외 뒤의 정상 스캔</b> — {@code EXCEPTION} 은 {@code DELIVERED} 보다 뒤에 선언돼 있어, '파손' 스캔 뒤에 정상
     *       배달된 상자가 EXCEPTION 에 고정됐다(12시간마다 영원히 폴링, 주문은 DISPATCHED 에 멈춤). 예외에서는 이동 · 배달 출발 ·
     *       배달 완료로 갈 수 있다.</li>
     *   <li><b>재배달 출발</b> — 부재({@code ATTEMPT_FAILED}) 다음의 배달 출발({@code OUT_FOR_DELIVERY})이 "뒤로 가는 전이" 로
     *       무시돼 배달 출발 알림이 빠지고 폴링 주기가 3시간으로 남았다.</li>
     * </ul>
     *
     * <p>[JA] 順序だけでは扱えない二つ — 例外の後の正常スキャン(以前は EXCEPTION に固定)と、不在の後の再配達出発(以前は無視)。
     */
    public boolean isProgressFrom(DeliveryStatus other) {
        if (other == null) {
            return true;
        }
        if (other.isTerminal() || this == other) {
            return false;   // 끝난 배송은 되돌리지 않는다
        }
        if (this == EXCEPTION || this == RETURNED) {
            return true;
        }
        if (other == EXCEPTION) {
            // 사고 뒤에 다시 움직이기 시작했다 — 집화 전 단계로는 돌아가지 않는다.
            return ordinal() >= IN_TRANSIT.ordinal() && ordinal() <= DELIVERED.ordinal();
        }
        if (other == ATTEMPT_FAILED && this == OUT_FOR_DELIVERY) {
            return true;    // 재배달 출발
        }
        return ordinal() > other.ordinal();
    }
}
