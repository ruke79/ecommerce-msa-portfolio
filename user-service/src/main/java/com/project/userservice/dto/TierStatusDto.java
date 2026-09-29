package com.project.userservice.dto;

import java.time.LocalDateTime;

import com.project.common.constants.event.CustomerTier;

import lombok.Builder;
import lombok.Getter;

/**
 * 내 등급 — 화면에 보여 줄 형태.
 *
 * <h3>🔴 무엇을 담지 <b>않는지</b>가 설계다</h3>
 *
 * <p>여기에는 <b>"다음 등급까지 남은 금액"이 없다.</b> 계산해서 넣기는 쉽지만, 그 한
 * 필드가 화면의 성격을 바꾼다 — "당신의 혜택" 이 "당신이 더 써야 할 금액" 이 된다.
 * 진행 게이지도 같은 이유로 없다.
 *
 * <p>대신 <b>근거는 전부 준다</b>: 지금 등급, 그 판단에 쓰인 금액, 기준 금액, 마지막
 * 계산 시각. "왜 내가 실버인가" 에 화면이 답할 수 있어야 문의가 CS 로 가지 않는다.
 * 사용자가 그 숫자로 직접 뺄셈을 하는 것은 막지 않는다 — 알고 싶은 사람에게 감추는 것과,
 * 우리가 먼저 들이미는 것은 다르다.
 *
 * <p>이 구분이 아마존 톤이다. 등급은 혜택이 도착하는 형태로만 드러나고, 등급 자체를
 * 점수처럼 전시하지 않는다({@link CustomerTier} 주석).
 */
@Getter
@Builder
public class TierStatusDto {

    /** 등급 이름({@code BASIC}/{@code SILVER}/…). 화면이 조건 분기에 쓴다. */
    private final String tier;

    /** 화면에 보일 한국어 이름. 서버가 정한다 — 두 곳에서 번역하면 어긋난다. */
    private final String displayName;

    /**
     * 등급을 마지막으로 계산한 시각.
     *
     * <p>이 값이 있어야 "오늘 큰 주문을 했는데 왜 그대로냐" 에 답할 수 있다. 배치는
     * 하루 한 번 돌고, 그 사실을 화면이 말해야 한다. 아직 한 번도 계산되지 않았으면
     * {@code null} 이다(가입 직후).
     */
    private final LocalDateTime computedAt;

    /** 등급 판단에 쓰인 최근 기간의 실결제액. */
    private final long recentPaidAmount;

    /** 가입 이후 결제된 주문 수. */
    private final long lifetimeOrderCount;

    /** 몇 개월을 보는가. 기준 금액과 함께 있어야 숫자가 뜻을 갖는다. */
    private final int months;

    private final long silverAmount;
    private final long goldAmount;
    private final long vipAmount;
}
