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
 *
 * <p>[JA] 一覧カードの在庫状態(docs/59 §5)。サーバーはカードごとにオプション(サイズ)の<b>今</b>の残数を送る — 他の注文の決済前予約を既に引いた値で、押さえられている数は
 * `onHold`(0 なら来ない)である。カードは選んだ色(sku)のサイズを合わせて一つを言う:
 * `sold_out` — 残りも押さえもない(または管理者が品切れにした)。`on_hold` — 残りはないが誰かが決済中。決済されなければ戻るので品切れとは言わない。
 * `low` — {@link LOW_STOCK} 個以下残っている。`null` — 十分にあるか数量が不明(数量のない応答なら何も言わない — 知らないものを品切れとは言わない)。
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
 *
 * <p>[JA] カート項目の在庫キー — `商品id:色id:サイズ`。サーバーの予約も同じキーである。足りない情報があれば null(問い合わせない)。
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
 *
 * <p>[JA] カート一行の在庫問題。問題がないか数量が不明なら null。
 * `discontinued` — 販売終了(商品削除 · オプション消滅)。`sold_out` — 残りも押さえもない(または管理者が品切れにした)。
 * `on_hold` — 残りはないが誰かが決済中 — すぐ戻りうる。`short` — 入れた数量が残数より多い(`count` = 残数)。
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
