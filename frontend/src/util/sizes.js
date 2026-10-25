/**
 * 사이즈 없는 상품(식품·생활용품 등) — 사이즈 행은 있지만 이름이 비어 있다.
 *
 * 카탈로그는 모든 SKU 에 사이즈 행을 둔다(재고·가격이 그 행에 있다). 사이즈가 없는 상품은 그 이름이 빈 문자열이라, 예전에는
 * 상품 화면에 빈 동그라미가 줄지어 나오고, 리뷰는 사이즈를 고르지 못해 쓸 수 없었으며, 리뷰 카드에는 "사이즈:" 만 찍혔다.
 */
export const sizeLabel = (size) => (size?.size ?? "").trim();

/** 고를 사이즈가 하나라도 있는가 — 전부 이름이 비어 있으면 사이즈 없는 상품이다. */
export const hasSizes = (sizes) => Array.isArray(sizes) && sizes.some((s) => sizeLabel(s) !== "");

/**
 * 색상(스타일)을 바꿀 때의 주소 — 고른 사이즈가 새 색상에도 있으면 **그 사이즈를 그대로** 싣는다.
 *
 * <p>🐛 2026-10-25 운영: 사이즈를 고르고 색상을 누르면 "사이즈를 선택해 주세요" 가 다시 나왔다. 색상 링크가 `?style=` 만 싣고
 * `size` 를 버렸다. 사이즈 줄은 색상(SKU)마다 따로라 **번호가 아니라 이름**으로 찾는다 — 새 색상에 그 이름이 없으면(그 색상에는 없는
 * 사이즈) 싣지 않고, 손님이 다시 고른다.
 *
 * <p>[JA] 色(スタイル)を変えるときの URL — 選んだサイズが新しい色にもあれば、そのサイズをそのまま載せる。以前は色のリンクが
 * `?style=` だけで `size` を捨てていた。サイズ行は色ごとに別なので番号ではなく名前で探す。新しい色に無ければ載せない。
 */
export function styleHref(slug, skus, fromStyle, fromSize, toStyle) {
    const base = `/product/${slug}/?style=${toStyle}`;
    if (fromSize === null || fromSize === undefined || fromSize === "") return base;
    const label = sizeLabel(skus?.[Number(fromStyle) || 0]?.sizes?.[Number(fromSize)]);
    if (!label) return base;
    const to = (skus?.[toStyle]?.sizes ?? []).findIndex((s) => sizeLabel(s) === label);
    return to >= 0 ? `${base}&size=${to}` : base;
}
