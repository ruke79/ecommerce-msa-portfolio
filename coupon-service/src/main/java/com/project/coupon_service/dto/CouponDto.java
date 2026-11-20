package com.project.coupon_service.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.project.common.constants.coupon.CouponScope;
import com.project.coupon_service.model.Coupon;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Data
@Builder
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class CouponDto {

    private String id;

    private String name;

    private String startDate;

    private String endDate;

    private int discount;

    /** ALL · CATEGORY · PRODUCT. 화면이 "일부 상품만" 배지를 그리는 데 쓴다. */
    // [JA] ALL · CATEGORY · PRODUCT。画面が「一部商品のみ」バッジを描くのに使う。
    private String scope;

    /** 대상 id 목록. scope 가 ALL 이면 비어 있다. */
    // [JA] 対象 id 一覧。scope が ALL なら空。
    private List<String> targets;

    /**
     * 지금 쓸 수 있는가(기간 안인가).
     *
     * <p>목록에서 만료된 쿠폰을 <b>감추지 않고 흐리게 보여주기 위해</b> 내려보낸다.
     * 목록에서 빼 버리면 "분명 쿠폰이 있었는데 사라졌다"가 되고, 사용자는 만료됐다는 것을
     * 알 방법이 없다.
     *
     * <p>[JA] 今使えるか(期間内か)。
     * <p>一覧で期限切れクーポンを<b>隠さず薄く表示するために</b>返す。一覧から外すと「確かにクーポンがあったのに消えた」となり、ユーザーは期限切れだと知る方法がない。
     */
    private boolean usable;

    public static CouponDto convertToDto(Coupon coupon) {
        return convertToDto(coupon, List.of(), LocalDateTime.now());
    }

    public static CouponDto convertToDto(Coupon coupon, List<String> targets, LocalDateTime now) {
        return CouponDto.builder()
                .id(Long.toString(coupon.getCouponId()))
                .name(coupon.getName())
                // 예전에는 `coupon.getStartDate().toString()` 이었다. 날짜는 널 허용
                // 컬럼이라 하나라도 비어 있으면 목록 조회 전체가 NPE 로 죽었다.
                // [JA] 以前は `coupon.getStartDate().toString()` だった。日付は NULL 許容
                //      カラムなので一つでも空なら一覧取得全体が NPE で落ちた。
                .startDate(coupon.getStartDate() == null ? null : coupon.getStartDate().toString())
                .endDate(coupon.getEndDate() == null ? null : coupon.getEndDate().toString())
                .discount(coupon.getDiscount())
                .scope(coupon.scope().name())
                .targets(targets == null ? List.of() : targets)
                .usable(coupon.isUsableAt(now))
                .build();
    }

    /** 편의 — 도메인 enum 으로 되돌려 읽는다. */
    // [JA] 便宜 — ドメイン enum に戻して読む。
    public CouponScope scopeOrAll() {
        if (scope == null) {
            return CouponScope.ALL;
        }
        try {
            return CouponScope.valueOf(scope);
        } catch (IllegalArgumentException e) {
            return CouponScope.ALL;
        }
    }
}
