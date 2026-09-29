import api from "util/api";
import { translate } from "i18n/I18nProvider";

/**
 * 알림 API.
 *
 * 게이트웨이가 JWT 를 검증한 뒤 User-Id 헤더를 채워 넣으므로, 프론트엔드는 사용자 id 를
 * 보내지 않는다. 보내더라도 게이트웨이가 덮어쓴다 — 남의 알림을 읽으려는 시도를 막기 위해서다.
 */
const BASE = "/notification-service/api/notifications";

export const fetchNotifications = async (size = 20) => {
  const { data } = await api.get(`${BASE}?size=${size}`);
  return data;
};

export const fetchUnreadCount = async () => {
  const { data } = await api.get(`${BASE}/unread-count`);
  return data.count;
};

/**
 * 전체 알림(공지·관리자 안내)인가.
 *
 * 목록은 개인 알림과 전체 알림을 합쳐서 내려오는데 읽음·삭제 엔드포인트가 서로 다르다 —
 * 전체 알림은 모두가 공유하는 행이라 개인 알림처럼 지울 수 없고, 개인별 표시만 남긴다.
 *
 * id 만으로는 어느 쪽인지 알 수 없다(두 테이블이 각자 TSID 를 쓴다). 그래서 서버가 scope 를
 * 실어 보낸다. 이걸 무시하고 개인 알림 경로로 보내면 404 가 나거나 — 더 나쁘게 — 우연히
 * 같은 id 의 남의 개인 알림을 건드린다.
 */
export const isBroadcast = (item) => item?.scope === "BROADCAST";

const pathFor = (item, suffix = "") =>
  isBroadcast(item) ? `${BASE}/broadcasts/${item.id}${suffix}` : `${BASE}/${item.id}${suffix}`;

export const markNotificationRead = async (item) => {
  await api.patch(pathFor(item, "/read"));
};

export const markAllNotificationsRead = async () => {
  const { data } = await api.patch(`${BASE}/read-all`);
  return data.updated;
};

/**
 * 목록에서 치운다.
 *
 * 전체 알림은 "내 목록에서만" 사라진다 — 공지 자체는 남는다. 한 사람의 삭제가 다른 사람의
 * 화면을 바꾸면 안 된다.
 */
export const deleteNotification = async (item) => {
  await api.delete(pathFor(item));
};

// ── 관리자: 전체 알림 발송 ──────────────────────────────────────────────────
//
// 🔒 서버는 /api/notifications/admin 접두사 아래를 읽기까지 관리자로 제한한다
// (AdminOnlyInterceptor). 화면의 ProtectedRoute 는 메뉴를 감추는 것일 뿐 인가가 아니다.

const ADMIN = `${BASE}/admin/broadcasts`;

/** 예약분·만료분까지 전부. 관리자는 안 보이는 것도 봐야 고칠 수 있다. */
export const fetchBroadcasts = async (limit = 50) => {
  const { data } = await api.get(`${ADMIN}?limit=${limit}`);
  return data;
};

/**
 * 관리자 안내를 보낸다.
 *
 * startsAt 이 비면 즉시, endsAt 이 비면 만료 없음이다. 점검 예고처럼 지나면 의미가 없는
 * 안내는 끝 시각을 넣어 두는 편이 낫다 — 사용자가 하나하나 지우게 하지 않는다.
 */
export const createBroadcast = async ({ title, content, url, startsAt, endsAt }) => {
  const { data } = await api.post(ADMIN, {
    title,
    content: content || null,
    url: url || null,
    startsAt: startsAt || null,
    endsAt: endsAt || null,
  });
  return data;
};

/** 모두에게서 내린다 — 사용자의 "치우기"와 다르다. */
export const deleteBroadcast = async (id) => {
  await api.delete(`${ADMIN}/${id}`);
};

export const fetchPreferences = async () => {
  const { data } = await api.get(`${BASE}/preferences`);
  return data;
};

export const updatePreferences = async (patch) => {
  const { data } = await api.patch(`${BASE}/preferences`, patch);
  return data;
};

/**
 * 인증번호를 요청한다.
 *
 * 429 는 정상적인 응답이다 — 남의 번호로 문자 폭탄을 보내는 것을 막는 제한에 걸린 것이므로,
 * 오류로 처리하지 말고 사용자에게 남은 시간을 보여 준다.
 */
export const requestPhoneCode = async ({ phone, region }) => {
  try {
    const { data } = await api.post(`${BASE}/phone/verification`, { phone, region });
    return { ok: true, ...data };
  } catch (error) {
    const status = error?.response?.status;
    const body = error?.response?.data ?? {};
    if (status === 429) {
      return { ok: false, tooMany: true, message: body.message, retryAfterSeconds: body.retryAfterSeconds };
    }
    return { ok: false, message: body.message ?? translate("notification_api.code_send_failed") };
  }
};

export const confirmPhoneCode = async ({ phone, region, code }) => {
  try {
    const { data } = await api.post(`${BASE}/phone/verification/confirm`, { phone, region, code });
    return { ok: true, ...data };
  } catch (error) {
    const body = error?.response?.data ?? {};
    return {
      ok: false,
      message: body.message ?? translate("notification_api.verify_failed"),
      attemptsLeft: body.attemptsLeft,
    };
  }
};
