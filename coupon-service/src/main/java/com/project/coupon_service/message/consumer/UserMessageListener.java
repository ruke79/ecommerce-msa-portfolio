package com.project.coupon_service.message.consumer;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.project.common.message.dto.request.UserCreatedRequest;
import com.project.coupon_service.config.WelcomeCouponProperties;
import com.project.coupon_service.dto.request.CouponRequest;
import com.project.coupon_service.model.Coupon;
import com.project.coupon_service.service.CouponService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserMessageListener {

    private final CouponService couponService;
    private final WelcomeCouponProperties welcomeCoupon;

    /**
     * 가입 축하 쿠폰을 발급한다.
     *
     * <p><b>예전에는 전부 하드코딩이었다</b> — 이 메서드 안에 딱 한 줄:
     *
     * <pre>
     *   new CouponRequest("30% discount coupon", now, now.plusDays(31), 30, userId)
     * </pre>
     *
     * 이름·할인율·유효기간이 모두 리터럴이라 할인율 하나 바꾸려고 코드를 고치고 서비스를
     * 다시 빌드·배포해야 했다. {@link WelcomeCouponProperties} 로 옮겼다.
     *
     * <p>이미 30% 쿠폰을 받은 기존 가입자 것은 건드리지 않는다. 쿠폰은 발급 시점의 이름과
     * 할인율을 자기 행에 갖고 있고, 적용은 그 이름으로 찾는다 — 정책이 바뀌어도 이미 나간
     * 쿠폰은 그대로 동작한다. (만료일이 있으므로 자연히 소멸한다.)
     */
    void userCreated(UserCreatedRequest createdUser) {

        if (!welcomeCoupon.isEnabled()) {
            log.debug("가입 쿠폰이 꺼져 있다 — 발급하지 않는다. userId={}", createdUser.getUserId());
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        CouponRequest req = new CouponRequest(
                welcomeCoupon.getName(),
                now,
                now.plusDays(welcomeCoupon.getValidDays()),
                welcomeCoupon.getDiscount(),
                Long.toString(createdUser.getUserId()));

        Coupon coupon = couponService.create(req);

        if (coupon == null) {
            // 이미 가입 쿠폰이 있다 — 같은 가입 이벤트의 재전달(커밋 전 리밸런스 · 재기동 · CDC 중복)이다. 정상이다.
            // 🐛 예전에는 던져서 재시도 뒤 user-created.DLT 와 경보(KafkaDeadLetterPublished)로 끝났다(CP4).
            // [JA] すでに加入クーポンがある — 同じ加入イベントの再配信で正常。以前は例外で DLT と警報になっていた。
            log.info("가입 쿠폰이 이미 있다 — 재전달로 보고 건너뛴다. userId={}", createdUser.getUserId());
            return;
        }
        log.debug("Coupon is created in coupon database with id: {}", coupon.getCouponId());
    }
}
