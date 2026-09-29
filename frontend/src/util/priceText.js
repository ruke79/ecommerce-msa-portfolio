/**
 * 가격 범위를 한 줄로 — "US$70.00" 또는 "US$70.00 – US$84.00". {@code money} 는 useMoney 가 돌려준 함수.
 *
 * <p>할인이 있으면 범위는 <b>할인가</b>다(skuPriceRange). 정가는 줄을 그어 따로 보여 준다.
 */
export function rangeText(range, money) {
    if (!range) return "";
    return range.min === range.max ? money(range.min) : `${money(range.min)} – ${money(range.max)}`;
}

/** 정가 범위 — 할인이 있을 때 줄 그어 보여 준다. */
export function rangeBeforeText(range, money) {
    if (!range) return "";
    return range.minBefore === range.maxBefore
        ? money(range.minBefore)
        : `${money(range.minBefore)} – ${money(range.maxBefore)}`;
}
