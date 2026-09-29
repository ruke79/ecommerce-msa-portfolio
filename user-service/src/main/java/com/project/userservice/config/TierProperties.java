package com.project.userservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.project.common.constants.event.CustomerTier;

import lombok.Getter;
import lombok.Setter;

/**
 * 등급 산정 정책.
 *
 * <p><b>왜 설정인가.</b> "3개월에 50만 원이면 골드" 는 판촉 정책이지 도메인 규칙이 아니다.
 * 마케팅이 기준을 바꾸고 싶을 때 코드를 고치고 배포해야 한다면, 실제로는 아무도 바꾸지
 * 않게 되고 기준은 처음 정한 값에 굳어 버린다. 가입 쿠폰 할인율
 * ({@code WelcomeCouponProperties})·신규 판정 기간과 같은 자리다.
 *
 * <p>금액은 <b>원 단위 실결제액</b>이다. 주문의 {@code total} 이 그 값이고, 배송비와
 * 세금이 포함돼 있다. 상품 금액만 따로 세지 않는 이유는 실무에서도 대부분 실결제액을
 * 쓰기 때문이다 — 사용자가 "내가 낸 돈" 으로 이해하는 숫자와 등급 기준이 어긋나면
 * 설명할 수 없는 등급이 된다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "user.tier")
public class TierProperties {

    /**
     * 몇 개월치를 보는가.
     *
     * <p>3개월이 기본이다. 너무 짧으면 한 번의 큰 주문에 등급이 출렁이고, 1년으로
     * 늘리면 이미 떠난 고객이 오래 상위 등급에 남는다.
     */
    private int months = 3;

    /** 실버 기준 금액(원). */
    private long silverAmount = 200_000L;

    /** 골드 기준 금액(원). */
    private long goldAmount = 500_000L;

    /** VIP 기준 금액(원). */
    private long vipAmount = 1_500_000L;

    /** 배치가 한 번에 받아오는 고객 수. order-service 의 상한(500)을 넘길 수 없다. */
    private int batchSize = 500;

    /**
     * 금액을 등급으로 바꾼다.
     *
     * <p>🔴 <b>기준이 뒤집혀 있어도 등급이 뛰지 않게</b> 위에서부터 내려온다. 설정 실수로
     * {@code goldAmount} 가 {@code vipAmount} 보다 크게 들어와도, 조건을 만족하는 가장
     * 높은 등급 하나가 잡힐 뿐 두 등급이 동시에 적용되는 일은 없다.
     *
     * <p>기준 금액에 <b>도달하면</b> 그 등급이다({@code >=}). "50만 원 이상 골드" 라고
     * 안내해 놓고 정확히 50만 원인 사람을 실버로 두면 그건 버그로 접수된다.
     */
    public CustomerTier tierFor(long amount) {
        // 산 적이 없으면 어떤 설정으로도 등급이 오르지 않는다. 기준 금액을 0 으로
        // 잘못 넣으면 전 회원이 VIP 가 되는데, 그 사고는 되돌리기가 매우 어렵다 —
        // 이미 발급된 쿠폰은 회수할 수 없다.
        if (amount <= 0) {
            return CustomerTier.BASIC;
        }
        if (amount >= vipAmount) {
            return CustomerTier.VIP;
        }
        if (amount >= goldAmount) {
            return CustomerTier.GOLD;
        }
        if (amount >= silverAmount) {
            return CustomerTier.SILVER;
        }
        return CustomerTier.BASIC;
    }
}
