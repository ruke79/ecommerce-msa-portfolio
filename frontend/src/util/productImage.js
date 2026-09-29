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
export function isRenderable(product) {
    const sku = product?.skus?.[0];
    return Boolean(sku) && Array.isArray(sku.sizes) && sku.sizes.length > 0;
}
