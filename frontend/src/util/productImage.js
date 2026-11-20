/**
 * 상품에서 대표 이미지 한 장을 안전하게 꺼낸다.
 *
 * <p>예전에는 화면 곳곳이 `product.skus[0].images[0]` 을 그대로 읽었다. sku 가 없거나
 * 이미지가 비어 있는 상품이 <b>하나만 섞여도</b> 그 자리에서 TypeError 가 나고, React 는
 * 렌더 트리를 통째로 버린다 — 홈이 흰 화면이 된다. 상품 등록이 이미지 업로드 단계에서
 * 실패하면(현재 AWS 키 교체가 미완이라 실제로 그럴 수 있다) 정확히 그런 상품이 생긴다.
 *
 * <p>없으면 null 을 돌려주고, 그린 쪽이 자리표시자를 넣는다.
 *
 * @param {object} product
 * @param {number} [skuIndex] 색상 인덱스. 범위를 벗어나면 첫 sku 로 떨어진다.
 * @returns {string|null}
 *
 * <p>[JA] 商品から代表画像を一枚安全に取り出す。以前は画面のあちこちが `product.skus[0].images[0]` をそのまま読んでいた。sku がないか画像が空の商品が
 * <b>一つ混ざるだけで</b>その場で TypeError が起き、React はレンダーツリーをまるごと捨てる — ホームが真っ白になる。商品登録が画像アップロード段階で
 * 失敗すると(現在 AWS キーの交換が未完了なので実際にありうる)まさにそういう商品ができる。なければ null を返し、描く側がプレースホルダーを入れる。
 * skuIndex は色インデックス。範囲外なら最初の sku に落ちる。
 */
export function firstImageOf(product, skuIndex = 0) {
    const skus = product?.skus;
    if (!Array.isArray(skus) || skus.length === 0) return null;

    const sku = skus[skuIndex] ?? skus[0];
    const images = sku?.images;
    if (!Array.isArray(images) || images.length === 0) return null;

    const first = images[0];
    return typeof first === "string" && first.length > 0 ? first : null;
}

/** 이 상품을 카드로 그릴 수 있는가. 가격·이미지가 없는 상품은 목록에서 뺀다. */
// [JA] この商品をカードとして描けるか。価格 · 画像がない商品は一覧から外す。
export function isRenderable(product) {
    const sku = product?.skus?.[0];
    return Boolean(sku) && Array.isArray(sku.sizes) && sku.sizes.length > 0;
}
