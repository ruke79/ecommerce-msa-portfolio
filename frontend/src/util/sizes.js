/**
 * 사이즈 없는 상품(식품·생활용품 등) — 사이즈 행은 있지만 이름이 비어 있다.
 *
 * 카탈로그는 모든 SKU 에 사이즈 행을 둔다(재고·가격이 그 행에 있다). 사이즈가 없는 상품은 그 이름이 빈 문자열이라, 예전에는
 * 상품 화면에 빈 동그라미가 줄지어 나오고, 리뷰는 사이즈를 고르지 못해 쓸 수 없었으며, 리뷰 카드에는 "사이즈:" 만 찍혔다.
 */
export const sizeLabel = (size) => (size?.size ?? "").trim();

/** 고를 사이즈가 하나라도 있는가 — 전부 이름이 비어 있으면 사이즈 없는 상품이다. */
export const hasSizes = (sizes) => Array.isArray(sizes) && sizes.some((s) => sizeLabel(s) !== "");
