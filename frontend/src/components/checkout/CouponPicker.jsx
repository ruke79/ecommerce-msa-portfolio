import { useMemo } from "react";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 보유 쿠폰 선택.
 *
 * <p><b>예전에는 자유 입력 칸 하나였다.</b> 그런데 이 서비스에는 "코드를 입력해 쿠폰을
 * 받는다"는 개념이 없다 — 쿠폰은 사용자별 행으로 발급되고, 적용은 그 행의 <b>이름</b>으로
 * 찾는다. 즉 입력 칸에 넣어서 통하는 값은 <b>이미 가지고 있는 쿠폰의 이름을 글자 그대로
 * 맞춘 것</b>뿐이었다. 사용자는 자기 쿠폰 이름을 알 방법이 없었다.
 *
 * <p>그래서 실제로는 화면이 목록의 <b>첫 번째 쿠폰을 자동으로 적용</b>하고 있었다. 쿠폰이
 * 한 장뿐이면 그럭저럭 동작했지만,
 *
 *   · 여러 장이면 사용자가 고를 수 없었고(항상 첫 번째),
 *   · 제약(카테고리·상품)이 생긴 지금은 <b>장바구니에 맞지 않는 쿠폰이 자동으로 적용</b>돼
 *     "쿠폰을 넣었는데 할인이 0원"으로 끝날 수 있다.
 *
 * <p>가진 것을 보여주고 고르게 한다. 만료된 쿠폰은 <b>목록에서 감추지 않고</b> 흐리게 둔다 —
 * 감추면 "쿠폰이 있었는데 사라졌다"가 되고 만료됐다는 것을 알 방법이 없다.
 */
const CouponPicker = ({ coupons, selected, onSelect, disabled }) => {
    const { t } = useTranslation();
    const list = useMemo(() => (Array.isArray(coupons) ? coupons : []), [coupons]);

    if (list.length === 0) {
        return (
            <p className="mx-3 my-4 text-sm text-slate-500">
                {t("checkout.coupon_picker.empty")}
            </p>
        );
    }

    return (
        <div className="mx-3 my-3 flex flex-col gap-2">
            <span className="text-sm text-slate-600">
                {t("checkout.coupon_picker.owned", { count: list.length })}
            </span>

            {list.map((coupon) => {
                const usable = coupon.usable !== false;
                const isSelected = selected === coupon.name;

                return (
                    <label
                        key={coupon.id ?? coupon.name}
                        className={`flex items-start gap-3 p-3 border rounded-lg transition
                            ${usable ? "cursor-pointer hover:border-amazon-blue_light" : "opacity-50 cursor-not-allowed"}
                            ${isSelected ? "border-amazon-orange bg-amber-50" : "border-slate-300"}`}
                    >
                        <input
                            type="radio"
                            name="coupon"
                            className="mt-1"
                            value={coupon.name}
                            checked={isSelected}
                            disabled={!usable || disabled}
                            onChange={() => onSelect(coupon.name)}
                        />
                        <span className="flex flex-col flex-1">
                            <span className="flex items-center gap-2">
                                <b className="text-base">{coupon.discount}%</b>
                                <span className="text-sm">{coupon.name}</span>
                                <ScopeBadge scope={coupon.scope} count={coupon.targets?.length} />
                            </span>
                            <span className="text-xs text-slate-500">
                                {usable
                                    ? formatPeriod(coupon.endDate, t)
                                    : t("checkout.coupon_picker.expired")}
                            </span>
                        </span>
                    </label>
                );
            })}

            {/* 고른 쿠폰을 되돌릴 방법이 있어야 한다. 라디오는 한번 고르면 해제가 안 된다. */}
            <label
                className={`flex items-center gap-3 p-3 border rounded-lg cursor-pointer transition
                    ${!selected ? "border-amazon-orange bg-amber-50" : "border-slate-300 hover:border-amazon-blue_light"}`}
            >
                <input
                    type="radio"
                    name="coupon"
                    value=""
                    checked={!selected}
                    disabled={disabled}
                    onChange={() => onSelect("")}
                />
                <span className="text-sm text-slate-600">{t("checkout.coupon_picker.none")}</span>
            </label>
        </div>
    );
};

/**
 * "이 쿠폰이 어디에 적용되는가"를 한눈에.
 *
 * <p>대상의 <b>이름</b>(카테고리명·상품명)까지 보여주려면 목록을 그릴 때마다 catalog 를
 * 한 번 더 조회해야 한다. 지금은 범위만 표시하고, 실제로 얼마가 깎였는지는 적용 후
 * 응답(eligibleSubtotal·discountAmount)으로 정확히 알려준다.
 */
const ScopeBadge = ({ scope, count }) => {
    const { t } = useTranslation();
    if (!scope || scope === "ALL") {
        return null;
    }
    const label = t(scope === "CATEGORY" ? "checkout.coupon_picker.scope.category" : "checkout.coupon_picker.scope.product");
    return (
        <span className="px-2 py-0.5 text-xs rounded-full bg-slate-200 text-slate-700">
            {label}
            {count > 0 ? ` ${t("checkout.coupon_picker.scope_count", { count })}` : ""}
        </span>
    );
};

function formatPeriod(endDate, t) {
    if (!endDate) {
        return t("checkout.coupon_picker.no_limit");
    }
    // 서버는 LocalDateTime.toString() 을 준다(2026-06-15T12:00). 날짜만 쓴다.
    return t("checkout.coupon_picker.until", { date: String(endDate).slice(0, 10) });
}

export default CouponPicker;
