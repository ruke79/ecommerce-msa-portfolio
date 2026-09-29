package com.project.userservice.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.project.common.constants.event.AudienceSegment;
import com.project.userservice.model.User;

import lombok.extern.slf4j.Slf4j;

/**
 * 사용자가 어떤 고객군에 속하는가.
 *
 * <p><b>왜 여기인가.</b> "신규가 며칠인가" 는 우리 서비스의 정책이고, 그 판단에 필요한
 * 가입일은 user-service 만 갖고 있다. 가입일을 다른 서비스에 넘겨 각자 계산하게 하면
 * 같은 규칙이 여러 곳에 생기고, 기준을 30일에서 14일로 바꿀 때 한 곳을 빠뜨린다.
 *
 * <p>쿠폰 서비스는 "이 사람의 세그먼트가 무엇인가" 만 물어보고, 기획전이 요구하는
 * 세그먼트와 대조한다.
 */
@Slf4j
@Service
public class SegmentService {

    /**
     * 가입 후 며칠까지를 신규로 보는가.
     *
     * <p>설정으로 두는 이유: 판촉 정책은 코드가 아니다. 가입 쿠폰의 할인율을 설정으로
     * 뺀 것과 같은 이유다({@code WelcomeCouponProperties}).
     */
    @Value("${user.segment.new-user-days:30}")
    private int newUserDays;

    /**
     * 이 사용자가 지금 속한 세그먼트.
     *
     * <p>{@link AudienceSegment#ALL} 은 <b>담지 않는다.</b> 모두가 항상 속하므로 목록에
     * 넣어 봤자 정보가 없고, 받는 쪽은 {@code ALL} 을 "조건 없음" 으로 따로 처리한다
     * ({@link AudienceSegment#matches}).
     *
     * <p>{@link AudienceSegment#NEW_USER} 와 {@link AudienceSegment#EXISTING_USER} 는
     * 언제나 <b>정확히 하나</b>가 붙는다 — 둘을 합치면 로그인 사용자 전체다. 구매 이력
     * ({@code PURCHASED}/{@code NEVER_PURCHASED})도 마찬가지다.
     *
     * <p>등급은 다르다. 일반 등급이면 <b>아무것도 붙지 않고</b>, 골드면 {@code TIER_SILVER}
     * 와 {@code TIER_GOLD} 가 <b>둘 다</b> 붙는다 — "실버 이상" 을 받는 쪽에서 단순 포함
     * 검사로 판정하게 하려는 것이다({@link com.project.common.constants.event.CustomerTier#segments()}).
     *
     * <p>🔴 등급과 구매 이력은 <b>여기서 계산하지 않는다.</b> 저장된 값을 읽을 뿐이다.
     * 배치({@link CustomerTierService})가 하루 한 번 채운다. 이 메서드는 쿠폰 발급
     * 경로에 있어서, 여기서 order-service 를 동기 호출하면 주문 서비스 장애가 쿠폰
     * 발급 장애로 번진다.
     */
    public List<AudienceSegment> segmentsOf(User user) {
        if (user == null) {
            return List.of();
        }

        List<AudienceSegment> segments = new ArrayList<>();

        // 축 1 — 가입 시점. 둘 중 정확히 하나.
        segments.add(isNew(user) ? AudienceSegment.NEW_USER : AudienceSegment.EXISTING_USER);

        // 축 2 — 구매 이력. 둘 중 정확히 하나.
        segments.add(user.hasPurchased() ? AudienceSegment.PURCHASED : AudienceSegment.NEVER_PURCHASED);

        // 축 3 — 등급. 없을 수도(일반), 여러 개일 수도(골드면 실버·골드 둘 다) 있다.
        // "실버 이상" 을 받는 쪽에서 단순 포함 검사로 판정할 수 있게 누적으로 준다.
        segments.addAll(user.tierOrBasic().segments());

        return List.copyOf(segments);
    }

    /**
     * 신규인가.
     *
     * <p>가입일이 없으면 <b>신규가 아니다.</b> createdAt 은 감사 필드라 비어 있을 수
     * 있는데(옛 데이터·직접 삽입), 그때 신규로 치면 <b>모든 옛 계정이 신규 전용 혜택을
     * 받는다</b> — 혜택을 잘못 주는 쪽이 못 주는 쪽보다 되돌리기 어렵다.
     */
    boolean isNew(User user) {
        LocalDateTime joinedAt = user.getCreatedAt();
        if (joinedAt == null) {
            log.debug("가입일이 없는 계정 — 신규로 보지 않는다. userId={}", user.getUserId());
            return false;
        }
        return joinedAt.isAfter(LocalDateTime.now().minus(Duration.ofDays(Math.max(0, newUserDays))));
    }
}
