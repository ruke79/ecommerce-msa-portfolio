import api from "util/api";

/**
 * 재입고 알림 (docs/53).
 *
 * <p>손님: 품절 옵션(상품·색상·사이즈)에 "재입고되면 알려 주세요" 를 걸고 거둔다. 신원은 게이트웨이가 토큰에서
 * 뽑아 User-Id 로 넣는다 — 본문에 사용자 값을 싣지 않는다.
 * <p>관리자: 품절 옵션을 보고 재고를 채운다. 0 에서 채우면 신청한 손님에게 알림이 간다.
 */
const SUBS = "/notification-service/api/notifications/restock-subscriptions";
const ADMIN_STOCK = "/catalog-service/api/content/admin/stock";

/** 이 상품에 걸어 둔 옵션들 — [{ colorId, size }]. 색상 없는 상품은 colorId 가 null. */
export const fetchRestockSubscriptions = async (productId) => {
  const { data } = await api.get(`${SUBS}?productId=${encodeURIComponent(productId)}`);
  return data ?? [];
};

export const subscribeRestock = async ({ productId, colorId, size, productName, productSlug }) => {
  const { data } = await api.post(SUBS, { productId, colorId: colorId ?? null, size, productName, productSlug });
  return data;
};

export const unsubscribeRestock = async ({ productId, colorId, size }) => {
  const params = new URLSearchParams({ productId: String(productId), size });
  if (colorId !== null && colorId !== undefined && colorId !== "") params.set("colorId", String(colorId));
  await api.delete(`${SUBS}?${params.toString()}`);
};

/** 관리자 재고 목록 — soldOutOnly 면 품절만. after 는 사이즈 id 커서. */
export const adminListStock = async ({ soldOutOnly = true, after } = {}) => {
  const params = new URLSearchParams({ soldOutOnly: String(soldOutOnly) });
  if (after) params.set("after", String(after));
  const { data } = await api.get(`${ADMIN_STOCK}?${params.toString()}`);
  return data ?? [];
};

/** 재고를 그 값으로 정한다(더하기가 아니다). */
export const adminSetStock = async (sizeId, quantity) => {
  const { data } = await api.put(`${ADMIN_STOCK}/${sizeId}`, { quantity });
  return data;
};
