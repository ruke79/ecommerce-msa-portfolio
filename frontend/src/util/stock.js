/**
 * 목록 카드의 재고 상태 (docs/59 §5).
 *
 * 서버가 카드마다 옵션(사이즈)의 **지금** 남은 수량을 보낸다 — 이미 다른 주문의 결제 전 예약을 뺀 값이고, 잡혀 있는 수는
 * `onHold`(0 이면 오지 않는다)다. 카드는 고른 색상(sku)의 사이즈들을 합쳐 하나를 말한다:
 *
 * - `sold_out` — 남은 것도 잡힌 것도 없다(또는 관리자가 품절로 내렸다).
 * - `on_hold`  — 남은 건 없지만 누가 결제 중이다. 결제되지 않으면 돌아오므로 품절이라고 하지 않는다.
 * - `low`      — {@link LOW_STOCK} 개 이하 남았다.
 * - `null`     — 넉넉하거나, 수량을 모른다(수량이 없는 응답이면 아무 말도 하지 않는다 — 모르는 것을 품절이라 하지 않는다).
 */
export const LOW_STOCK = 3;

export function stockStateOf(product, sku) {
    if (product?.status === "SOLD_OUT") {
        return { kind: "sold_out" };
    }
    const sizes = sku?.sizes;
    if (!Array.isArray(sizes) || sizes.length === 0 || sizes.some((s) => typeof s?.quantity !== "number")) {
        return null;
    }
    const left = sizes.reduce((sum, s) => sum + Math.max(0, s.quantity), 0);
    const held = sizes.reduce((sum, s) => sum + Math.max(0, s.onHold ?? 0), 0);
    if (left <= 0) {
        return { kind: held > 0 ? "on_hold" : "sold_out" };
    }
    if (left <= LOW_STOCK) {
        return { kind: "low", count: left };
    }
    return null;
}

/**
 * 장바구니 항목의 재고 키 — `상품id:색상id:사이즈`. 서버의 예약도 같은 키다. 모자란 정보가 있으면 null(묻지 않는다).
 */
export function cartStockKey(item) {
    const id = item?.id;
    const colorId = item?.color?.id;
    const size = item?.size;
    if (id == null || colorId == null || size == null || size === "") {
        return null;
    }
    return `${id}:${colorId}:${size}`;
}

/**
 * 장바구니 한 줄의 재고 문제. 문제가 없거나 수량을 모르면 null.
 *
 * - `discontinued` — 판매가 끝났다(상품 삭제·옵션이 사라짐).
 * - `sold_out`     — 남은 것도 잡힌 것도 없다(또는 관리자가 품절로 내렸다).
 * - `on_hold`      — 남은 건 없지만 누가 결제 중이다 — 곧 돌아올 수 있다.
 * - `short`        — 담은 수량이 남은 수량보다 많다(`count` = 남은 수량).
 */
export function cartLineStockOf(item) {
    if (item?.stockStatus === "DELETED" || item?.stockStatus === "UNAVAILABLE") {
        return { kind: "discontinued" };
    }
    if (typeof item?.quantity !== "number") {
        return null;
    }
    const left = Math.max(0, item.quantity);
    if (item.stockStatus === "SOLD_OUT" || left <= 0) {
        return { kind: (item.onHold ?? 0) > 0 && item.stockStatus !== "SOLD_OUT" ? "on_hold" : "sold_out" };
    }
    if ((item.qty ?? 1) > left) {
        return { kind: "short", count: left };
    }
    return null;
}
