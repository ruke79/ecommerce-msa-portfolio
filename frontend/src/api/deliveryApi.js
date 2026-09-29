import api from "util/api";
import { translate } from "i18n/I18nProvider";

/** 배송 조회·주소 변경·취소. 사용자 식별은 게이트웨이가 User-Id 헤더로 넣어 준다. */
const BASE = "/delivery-service/api/deliveries";

export const fetchDeliveries = async (size = 20) => {
  const { data } = await api.get(`${BASE}?size=${size}`);
  return data;
};

export const fetchDeliveryByOrder = async (orderId) => {
  const { data } = await api.get(`${BASE}/order/${orderId}`);
  return data;
};

export const fetchCarriers = async () => {
  const { data } = await api.get(`${BASE}/carriers`);
  return data;
};

/**
 * 배송지 변경.
 *
 * 409 는 오류가 아니라 **지금은 할 수 없다**는 뜻이다 — 이미 발송된 것이다.
 * 400 과 구분해야 "잘못 입력했나?" 대신 "이미 발송되었습니다"를 띄울 수 있다.
 */
export const changeDeliveryAddress = async (orderId, payload) => {
  try {
    const { data } = await api.patch(`${BASE}/order/${orderId}/address`, payload);
    return { ok: true, ...data };
  } catch (error) {
    const status = error?.response?.status;
    const body = error?.response?.data ?? {};
    return {
      ok: false,
      alreadyDispatched: status === 409,
      message: body.message ?? translate("delivery_api.change_failed"),
    };
  }
};

// ── 관리자 ─────────────────────────────────────────────────────────────

/**
 * 관리자 목록. 기본은 **송장을 기다리는 배송**(오래된 것부터), awaiting=false 면 최근 전체.
 * 관리자 여부는 서버가 User-Role 로 안다 — 여기서 보내는 것은 없다.
 */
export const adminListDeliveries = async ({ awaiting = true, size = 50 } = {}) => {
  const { data } = await api.get(`${BASE}/admin?awaiting=${awaiting}&size=${size}`);
  return data;
};

/**
 * 송장 등록 — 이 순간부터 폴링이 시작되고 주문은 DISPATCHED 로 간다.
 * 보내는 것은 택배사 코드와 송장번호뿐이다. 상자(sequenceNo)를 비우면 송장 없는 첫 상자에 붙는다.
 */
export const registerTracking = async (orderId, { carrierCode, trackingNumber, sequenceNo = null }) => {
  const body = { carrierCode, trackingNumber };
  if (sequenceNo != null) body.sequenceNo = sequenceNo;
  const { data } = await api.post(`${BASE}/order/${orderId}/tracking`, body);
  return data;
};

/**
 * 결제됐는데 배송이 없는 주문의 배송을 만든다 — 복구용.
 *
 * 배송지·품목은 서버가 **주문에서 다시 읽는다.** 여기서 보내는 것은 주문 번호뿐이다 —
 * 관리자라도 배송지를 지정할 수 있으면 남의 물건을 자기 주소로 보낼 수 있다.
 */
export const adminCreateDeliveryForOrder = async (orderId) => {
  const { data } = await api.post(`${BASE}/admin/order/${orderId}`);
  return data;
};

export const cancelDelivery = async (orderId) => {
  try {
    const { data } = await api.post(`${BASE}/order/${orderId}/cancel`);
    return { ok: true, ...data };
  } catch (error) {
    const status = error?.response?.status;
    const body = error?.response?.data ?? {};
    return {
      ok: false,
      alreadyDispatched: status === 409,
      message: body.message ?? translate("delivery_api.cancel_failed"),
    };
  }
};
