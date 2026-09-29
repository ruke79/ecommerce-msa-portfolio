import {
    CheckIcon,
    ChevronDownIcon,
    StarIcon,
} from "@heroicons/react/24/solid";
import { Tooltip } from "@mui/material";
import { useEffect, useState } from "react";
import { useTranslation } from "i18n/I18nProvider";

/**
 * `price` 파라미터를 [최소, 최대] 로 나눈다. 없는 경계는 빈 문자열이다.
 *
 * <p>둘을 `_` 로 이어 붙인 한 문자열이고 <b>한쪽만 있을 수 있다</b> — `500_` 은
 * "500 이상", `_50` 은 "50 이하"다.
 */
export function splitPriceParam(priceParam) {
    const [min = "", max = ""] = String(priceParam ?? "").split("_");
    return [min, max];
}

/**
 * 가격대 버튼. 상한이 없는 마지막 칸은 빈 문자열이다 — 0 이 아니다.
 *
 * <p>🐛 `multiPriceHandler(500, "")` 가 만드는 `price=500_` 을 예전 요청 생성기가
 * `highPrice: 0` 으로 바꿨다(browse.jsx 의 `Number("") || 0`). `500 ~ 0` 은 아무것도
 * 맞지 않는 범위이고, 서버는 그 "없음"을 <b>전체 상품</b>으로 뒤집었다.
 */
export const PRICE_RANGES = [
    { min: 0, max: 10, height: "15%" },
    { min: 10, max: 50, height: "30%" },
    { min: 50, max: 100, height: "50%" },
    { min: 100, max: 500, height: "75%" },
    { min: 500, max: "", height: "100%" },
];

const rangeLabel = (t, { min, max }) => {
    if (max === "") return t("browse.price_range.over", { min });
    if (min === 0) return t("browse.price_range.under", { max });
    return t("browse.price_range.between", { min, max });
};

const HeadingFilter = ({
    priceHandler,
    multiPriceHandler,
    shippingHandler,
    ratingHandler,
    sortHandler,
    searchParams
}) => {

    const [show, setShow] = useState(false);

    const { t } = useTranslation();

    const priceParam  = searchParams.get('price');
    const sortParam  = searchParams.get('sort');

    const [minParam, maxParam] = splitPriceParam(priceParam);

    /**
     * 입력칸의 값.
     *
     * <p>🐛 예전에는 `value={minPrice || priceParam?.split("_")[0]}` 이었다. 지운 칸은
     * 빈 문자열이고 빈 문자열은 falsy 라 <b>URL 의 옛 값이 되살아났다</b> — 숫자를 지우면
     * 그 자리에 방금 지운 값이 다시 나타나서 <b>가격 칸을 비울 수가 없었다.</b>
     *
     * <p>입력칸의 주인은 이 state 하나다. 바깥에서 URL 이 바뀌면(Clear All · 뒤로가기 ·
     * 가격대 버튼) 아래 effect 가 맞춰 준다.
     */
    const [minDraft, setMinDraft] = useState(minParam);
    const [maxDraft, setMaxDraft] = useState(maxParam);

    useEffect(() => {
        setMinDraft(minParam);
        setMaxDraft(maxParam);
    }, [minParam, maxParam]);

    /**
     * 🐛 예전에는 `Number(searchParams.get(...))` 를 문자열과 비교했다 —
     * `0 === "0"` 은 언제나 거짓이라 <b>체크 상태가 한 번도 켜지지 않았다.</b>
     * 게다가 `defaultChecked` 라서 URL 이 바뀌어도(Clear All) 체크가 그대로 남았다.
     */
    const freeShippingOn = searchParams.get('shipping') === "0";
    const ratingOn = searchParams.get('rating') === "4";

    const sortQuery = sortParam || "Recomended";


    return (
        <div className="w-full flex flex-col md:flex-row md:items-end gap-x-6">
            <div>
                <span className="mr-2">{t("browse.price")}</span>
                <input
                    className="mx-1 w-20 rounded border py-1.5 px-2 outline-none"
                    type="number"
                    placeholder={t("browse.price.min")}
                    min="0"
                    aria-label={t("browse.price.min")}
                    onChange={(e) => { setMinDraft(e.target.value); priceHandler(e.target.value, "min", 500); }}
                    value={minDraft}
                />
                <input
                    className="mx-1 w-20 rounded border py-1.5 px-2 outline-none"
                    type="number"
                    placeholder={t("browse.price.max")}
                    // 🐛 `max="0"` 이었다 — 위 칸의 `min="0"` 을 복사하며 이름만 바뀐 것이다.
                    //    "최대 가격은 0 이하여야 한다"는 뜻이라, 양수를 넣는 순간 입력칸이
                    //    유효하지 않은 상태가 되고 브라우저 스피너가 0 위로 올라가지 않는다.
                    min="0"
                    aria-label={t("browse.price.max")}
                    onChange={(e) => { setMaxDraft(e.target.value); priceHandler(e.target.value, "max", 500); }}
                    value={maxDraft}
                />
            </div>

            <div>
                {PRICE_RANGES.map((range) => {
                    // 지금 고른 범위인가. 눌러 놓고도 어느 칸인지 알 수 없던 자리다.
                    const active =
                        String(range.min) === minParam && String(range.max) === maxParam;
                    return (
                        <Tooltip
                            key={`${range.min}_${range.max}`}
                            title={<h2>{rangeLabel(t, range)}</h2>}
                            placement="top"
                            arrow
                            onClick={() => multiPriceHandler(range.min, range.max)}
                        >
                            <button
                                type="button"
                                className="tooltip_btn"
                                aria-label={rangeLabel(t, range)}
                                aria-pressed={active}
                            >
                                <span
                                    style={{ height: range.height }}
                                    className={active ? "bg-amazon-blue_light" : ""}
                                ></span>
                            </button>
                        </Tooltip>
                    );
                })}
            </div>

            <div>
                <label
                    htmlFor="shipping"
                    className="flex items-center cursor-pointer"
                >
                    <input
                        className="w-4 h-4 mr-1.5"
                        type="checkbox"
                        name="shipping"
                        id="shipping"
                        checked={freeShippingOn}
                        // 빈 객체는 "이 필터를 지운다" 는 뜻이다(browse.jsx 의 toggleParam).
                        onChange={() => shippingHandler(freeShippingOn ? {} : "0")}
                    />
                    {t("browse.filter.free_shipping")}
                </label>
            </div>

            <div>
                <label
                    htmlFor="rating"
                    className="flex items-center cursor-pointer"
                >
                    <input
                        className="mr-1 h-4 w-4"
                        type="checkbox"
                        name="rating"
                        id="rating"
                        checked={ratingOn}
                        onChange={() => ratingHandler(ratingOn ? {} : "4")}
                    />
                    <StarIcon className="h-5 w-5 fill-[#FACF19]" />
                    <StarIcon className="h-5 w-5 fill-[#FACF19]" />
                    <StarIcon className="h-5 w-5 fill-[#FACF19]" />
                    <StarIcon className="h-5 w-5 fill-[#FACF19]" />
                    <span className="ml-1">{t("browse.filter.rating_and_up")}</span>
                </label>
            </div>

            <div
                className="dropdown_sortby ml-auto"
                onMouseOver={() => setShow(true)}
                onMouseLeave={() => setShow(false)}
            >
                <span>{t("browse.sort_by")}</span>
                <button className="flex items-center ml-1">
                    {t(sortName(sortQuery))}{" "}
                    <ChevronDownIcon className="w-4 h-4 ml-2" />
                </button>
                {show && (
                    <ul>
                        {sortingOptions.map((option, i) => (
                            <li
                                key={i}
                                onClick={() => sortHandler(option.value)}
                                className={`flex justify-between ${
                                    sortQuery === option.value
                                        ? "font-semibold"
                                        : ""
                                }`}
                            >
                                <a>{t(option.key)}</a>
                                {sortQuery === option.value ? (
                                    <CheckIcon className="w-5 h-5" />
                                ) : (
                                    ""
                                )}
                            </li>
                        ))}
                    </ul>
                )}
            </div>
        </div>
    );
};

export default HeadingFilter;

/**
 * 정렬 옵션.
 *
 * <p>표시 이름이 아니라 <b>문구 키</b>를 담는다. 이 배열은 모듈 로드 시점에 한 번만
 * 평가되므로, 번역된 문자열을 여기에 넣으면 <b>언어를 바꿔도 바뀌지 않는다.</b>
 *
 * <p>{@code value} 는 서버로 가는 질의 값이라 번역하지 않는다 — 화면 언어가 API 계약을
 * 바꾸면 안 된다. ({@code priceLowToHight} 의 오타는 서버와 맞춰진 값이라 그대로 둔다.)
 */
const sortingOptions = [
    { key: "browse.sort.recommend", value: "" },
    { key: "browse.sort.popular", value: "popular" },
    { key: "browse.sort.newest", value: "newest" },
    { key: "browse.sort.topSelling", value: "topSelling" },
    { key: "browse.sort.topReviewed", value: "topReviewed" },
    { key: "browse.sort.priceLowToHight", value: "priceLowToHight" },
    { key: "browse.sort.priceHighToLow", value: "priceHighToLow" },
];

/** 지금 정렬값에 해당하는 문구 키. 모르는 값이면 기본(추천순)으로 떨어진다. */
const sortName = (value) =>
    sortingOptions.find((x) => x.value === value)?.key ?? "browse.sort.recommend";
