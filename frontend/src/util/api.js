import axios from "axios";
import { getCurrentLang, translate } from "i18n/I18nProvider";
import { QueryClient, QueryCache } from "@tanstack/react-query";
import toast from 'react-hot-toast';
import TokenUtil from "./tokenUtil"
import { API_BASE } from "./apiBase"

/**
 * 조회 실패를 오류 화면(ErrorBoundary)으로 올릴지 — <b>그릴 데이터가 없을 때만</b>.
 *
 * 🐛 예전에는 언제나(`throwOnError: true`) 올렸다. 이미 주문을 그려 둔 화면이 결제 확정을 기다리며 다시 읽거나, 창으로 돌아와 다시
 * 읽다가 한 번 실패하면(배포 중 연결 끊김·네트워크 전환) 화면 전체가 "問題が発生しました / Network Error" 로 바뀌었다. 아래
 * onError 가 "그려 둔 데이터가 있으면 알림만" 을 하려 했지만, 오류 화면이 먼저 덮었다.
 */
export const throwWithoutData = (error, query) => query.state.data === undefined;

/** 응답이 없는 실패(연결 끊김·배포 중 재시작)만 두 번 더 — 서버가 답한 4xx·5xx 는 다시 해도 같다. */
export const retryNetworkErrors = (failureCount, error) => !error?.response && failureCount < 2;

export const queryClient = new QueryClient(
  {
    defaultOptions: {
      queries: {
        retry: retryNetworkErrors,
        throwOnError: throwWithoutData,
      },
      mutations: {
        throwOnError: true,
      },
    },
    queryCache: new QueryCache({
      onError: (error, query) => {
        // console.log 였다 — 운영 번들에서 지워져 조회 실패가 아무 데도 남지 않았다.
        console.error("[query]", error.message);
        if (query.state.data !== undefined && !query.meta?.silentRefetch) {
          // 이미 그려 둔 데이터를 새로 받다 실패한 경우다. 서버가 이유를 줬으면 그것을 붙인다.
          toast.error(translate("query.refetch_failed", {
            detail: error?.response?.data?.message ?? error?.message ?? "",
          }));
        }
      },
    }),
  }
);





// Create an Axios instance
const api = axios.create({
  baseURL: API_BASE,
  headers: {    
    "Content-Type": "application/json",
    Accept: "application/json",
    'MultiLogin' : 'false',
  },
  withCredentials: true,
  // 요청 무한 대기 방지(느린 게이트웨이/서비스 시 탭 hang 차단).
  // 주의: validateStatus 기본값(>=400 reject)을 유지해야 401 자동 리프레시 인터셉터가 동작한다.
  timeout: 15000,
});



// 서버가 돌려주는 문구(오류·안내)를 화면 언어로 — 서버는 Accept-Language 로 고른다(docs/60 §4). 요청마다 그 순간의 언어.
/**
 * 경로는 언제나 사이트 루트 기준이다 — 맨 앞 `/` 를 빠뜨린 경로를 바로잡는다.
 *
 * 🐛 검색이 `getRequest("catalog-service/api/search")` 였다. API_BASE 가 비어 있으면(같은 출처 — 상대 경로) 이 경로는 **지금
 * 페이지 기준**으로 풀린다: 상품 화면(`/product/…`)에서 브레드크럼의 카테고리를 누르면 `/product/catalog-service/api/search`
 * 로 나가 프론트 nginx 가 index.html 을 돌려줬고, 검색 결과가 비었다. API_BASE 를 넣고 빌드했을 때는 드러나지 않았다.
 */
export function rootRelative(url) {
  if (typeof url !== "string" || url === "" || url.startsWith("/") || /^[a-z][a-z0-9+.-]*:/i.test(url)) return url;
  return "/" + url;
}

api.interceptors.request.use((config) => {
  config.url = rootRelative(config.url);
  config.headers = config.headers ?? {};
  if (!config.headers["Accept-Language"]) {
    config.headers["Accept-Language"] = getCurrentLang();
  }
  return config;
});

// axios 시그니처를 정확히 맞춘 래퍼.
//  - GET/DELETE: (url, config)  → config에 params 등을 그대로 전달
//  - POST/PUT/PATCH: (url, data, config)
// contentType은 per-request 헤더로만 반영하고, timeout/validateStatus는 인스턴스 기본값을 따른다.
// (validateStatus를 <500으로 덮어쓰면 4xx가 resolve되어 401 자동 리프레시 인터셉터가 깨지므로 건드리지 않는다.)

function withContentType(requestConfig = {}, contentType) {
  if (!contentType) return requestConfig;
  return {
    ...requestConfig,
    headers: { "Content-Type": contentType, ...(requestConfig.headers || {}) },
  };
}

export function getRequest(URL, requestConfig = {}, contentType) {
  return api.get(`${URL}`, withContentType(requestConfig, contentType)).then((response) => response);
}

// 네 번째 인자(requestConfig)는 이 요청에만 적용할 axios 설정이다. 헤더를 요청 단위로
// 실을 수 있어야 인스턴스의 전역 기본값(api.defaults.headers)을 건드리지 않아도 된다.
export function postRequest(URL, payload, contentType = "application/json", requestConfig = {}) {
  return api.post(`${URL}`, payload, withContentType(requestConfig, contentType)).then((response) => response);
}

export function putRequest(URL, payload, contentType = "application/json", requestConfig = {}) {
  return api.put(`${URL}`, payload, withContentType(requestConfig, contentType)).then((response) => response);
}

export function patchRequest(URL, payload, contentType = "application/json", requestConfig = {}) {
  return api.patch(`${URL}`, payload, withContentType(requestConfig, contentType)).then((response) => response);
}

export function deleteRequest(URL, requestConfig = {}) {
  return api.delete(`${URL}`, requestConfig).then((response) => response);
}

// ── 도메인 호출 ────────────────────────────────────────────────────────────
//
// 예전에는 아래 함수들이 전부 이런 모양이었다:
//
//     try { const { data } = await getRequest(...); return data; }
//     catch (error) { console.log("erorr >>>", error.response?.data.message); }
//
// 세 가지가 잘못돼 있었다.
//
//  1. **오류를 삼킨다.** catch 가 아무것도 던지지 않고 끝나므로 함수는 `undefined` 를
//     반환한다. 호출부는 그걸 성공으로 받아 `orderData.products.map(...)` 을 하다가
//     "Cannot read properties of undefined" 로 죽는다. 실제 원인(401·500·타임아웃)은
//     콘솔에만 남고 화면에는 엉뚱한 오류가 뜬다.
//  2. **React Query 를 무력화한다.** queryClient 는 `throwOnError: true` 로 설정돼 있어
//     쿼리 실패를 ErrorBoundary 로 올리게 돼 있는데, 여기서 오류를 먹어 버리면
//     react-query 입장에서는 항상 성공(값이 undefined 인 성공)이다. 재시도도, 오류 UI 도
//     동작하지 않는다.
//  3. **catch 안에서 다시 터진다.** `error.response?.data.message` 의 옵셔널 체이닝은
//     `response` 까지만 보호한다. 서버가 본문 없이 500 을 주면 `data` 가 undefined 라
//     `.message` 접근에서 TypeError 가 나고, 원래 오류는 그 TypeError 에 가려진다.
//
// 그래서 여기서는 **잡지 않는다.** 오류는 호출부(useQuery/useMutation → ErrorBoundary)로
// 그대로 올라간다. 화면에 어떻게 보여줄지는 화면이 정할 일이지 데이터 계층이 정할 일이 아니다.

// 신원(누구의 장바구니인가)은 보내지 않는다 — 서버가 검증된 토큰의 uid 로 정한다. 예전에는 본문에 email 을
// 실었고 서버가 그것을 믿어, 남의 이메일을 넣으면 남의 장바구니를 덮어쓸 수 있었다(docs/38 §1.6).
export const saveCart = async (products) => {
  const { data } = await postRequest("/cart-service/api/cart/savecart", { products });
  return data;
};

export const saveShippingAddress = async (address) => {
  const { data } = await postRequest("/user-service/api/user/profile/save_address", { address });
  return data;
};

/**
 * 배송지 수정. 본인 것만 — 남의 것이면 서버가 403 이다. 기본 배송지 여부는 이 호출로
 * 바뀌지 않는다(그건 selectShippingAddress 다).
 */
export const updateShippingAddress = async (addressId, address) => {
  const { data } = await putRequest(`/user-service/api/user/profile/address/${addressId}`, { address });
  return data;
};

export const selectShippingAddress = async (addressId) => {
  const { data } = await getRequest(`/user-service/api/user/profile/select/${addressId}`);
  return data;
};

export const deleteAddress = async (addressId) => {
  const { data } = await deleteRequest(`/user-service/api/user/profile/delete/${addressId}`);
  return data;
};

// 🔒 이메일을 보내지 않는다. 누구의 쿠폰인지는 게이트웨이가 검증한 토큰의 uid(User-Id 헤더)가
// 정한다. 예전에는 본문에 userEmail 을 실었고 서버가 그걸 그대로 믿어서, 남의 이메일을
// 넣으면 남의 쿠폰이 적용됐다(BOLA). 서버는 이제 그 필드를 읽지 않는다.
export const applyCoupon = async (cartTotal, coupon) => {
  const { data } = await postRequest("/coupon-service/api/coupon/applycoupon", {
    totalPrice: cartTotal,
    couponName: coupon,
  });
  return data;
};

// 🔒 신원(email/userId)은 더 이상 보내지 않는다. 서버는 게이트웨이가 검증한 토큰에서
// 신원을 얻는다(토큰의 uid — User-Id 헤더). 예전에는 이 값들을 쿼리로 보냈고 서버가 그대로 믿어서,
// 남의 이메일을 넣으면 그 사람의 주문·장바구니·쿠폰이 그대로 나왔다(docs/27).
// 클라이언트가 계속 보내면 "이 값이 신원을 정한다"는 오해를 남기므로 아예 뺀다.
export const getOrders = async (filter) => {
  const { data } = await getRequest("/order-service/api/order/orders", {
    params: { filter },
  });
  return data;
};

/**
 * 주문 목록 — 커서 페이징.
 *
 * <p>예전 `getOrders` 는 고객의 주문을 **전부** 받았다. 게다가 서버가 주문마다 DB 조회 +
 * user-service 호출 + catalog 호출을 다시 했고(N+1), 상태로 거를 때는 조건에 맞지 않는
 * 주문이 **null 로 목록에 섞여** 왔다 — "Paid" 를 골라도 행 수는 그대로이고 대부분이 빈
 * 줄이었다(화면이 `order?.id` 로 물음표를 붙여 가며 그리던 이유).
 *
 * @returns {{contents: Array, nextCursor: number}} nextCursor 가 -1 이면 마지막 묶음이다.
 */
export const getOrdersPage = async ({ filter = "", cursor, size = 10 } = {}) => {
  const { data } = await getRequest("/order-service/api/order/orders/page", {
    params: { filter, cursor, size },
  });
  return data;
};

export const getOrder = async (orderId) => {
  const { data } = await getRequest(`/order-service/api/order/${orderId}`);
  return data;
};

/**
 * 구매 확정 — DELIVERED → COMPLETED (docs/25 §12).
 *
 * <p>본문이 없다. 어느 주문인지는 경로, 누가인지는 게이트웨이가 토큰에서 뽑는다. 남의 주문이면
 * 404, 배달 완료가 아니면 400 이고, 이미 확정된 주문이면 200 으로 그대로 온다(멱등).
 */
export const confirmPurchase = async (orderId) => {
  const { data } = await postRequest(`/order-service/api/order/${orderId}/confirm`, {});
  return data;
};

/**
 * 장바구니 항목들의 지금 재고 — 키는 `상품id:색상id:사이즈`(util/stock.js 의 cartStockKey). 응답은
 * `{키: {quantity, onHold, status}}` 이고 quantity 는 다른 주문의 결제 전 예약을 뺀 값이다(docs/59 §5).
 * 같은 이름의 쿼리 파라미터를 여러 번 — axios 의 기본 배열 직렬화(`items[]=`)는 스프링이 읽지 못한다.
 */
export const getCartStock = async (keys) => {
  const params = new URLSearchParams();
  keys.forEach((k) => params.append("items", k));
  const { data } = await getRequest(`/catalog-service/api/stock?${params.toString()}`);
  return data;
};

// ── 서버가 장바구니의 기준 — 담기·수량·삭제(docs/40 0-b) ─────────────────────────────────────────────
// 응답은 언제나 **지금 장바구니 전체**(loadCart 와 같은 모양, 없으면 null)다 — 화면은 그것으로 바꾼다(syncedFromServer).
// 누구의 장바구니인지는 보내지 않는다(토큰의 uid). 줄은 상품·색상·사이즈로 가리킨다 — 담을 때와 같은 규칙이다.

/** 담기 — 같은 줄이면 수량을 이 값으로. 본문은 catalog 의 담기 응답(`/catalog-service/api/cart/{id}`) + qty·_uid. */
/**
 * 응답이 **아예 없는** 실패(연결 끊김·ERR_NETWORK_CHANGED·배포 중 재시작)면 잠깐 뒤 **한 번** 더 보낸다.
 *
 * <p>장바구니의 담기·수량·삭제에만 쓴다 — 셋 다 "이 줄의 수량을 이 값으로" 라서 두 번 가도 결과가 같다(멱등). 첫 요청이 서버에
 * 닿고 응답만 잃었어도 안전하다. 서버가 답한 4xx·5xx 는 다시 해도 같으니 그대로 올린다(retryNetworkErrors 와 같은 기준).
 * 취소된 요청은 다시 보내지 않는다.
 */
export const CART_RETRY_DELAY_MS = 600;
export const retryOnceOnNetworkError = async (send, delayMs = CART_RETRY_DELAY_MS) => {
  try {
    return await send();
  } catch (error) {
    if (error?.response || error?.code === "ERR_CANCELED") throw error;
    await new Promise((resolve) => setTimeout(resolve, delayMs));
    return send();
  }
};

export const putCartItem = async (item) => {
  const { data } = await retryOnceOnNetworkError(() => putRequest("/cart-service/api/cart/items", item));
  return data;
};

const cartLineParams = ({ productId, colorId, size }) => ({ productId, colorId, size: size ?? "" });

/** 수량 — 0 이하면 뺀다. */
export const setCartItemQty = async ({ productId, colorId, size, qty }) => {
  const { data } = await retryOnceOnNetworkError(() => api.patch("/cart-service/api/cart/items", null, {
    params: { ...cartLineParams({ productId, colorId, size }), qty },
  }));
  return data;
};

export const removeCartItem = async (line) => {
  const { data } = await retryOnceOnNetworkError(() => api.delete("/cart-service/api/cart/items", { params: cartLineParams(line) }));
  return data;
};

export const loadCart = async () => {
  const { data } = await getRequest("/cart-service/api/cart/loadcart");
  return data;
};

export const loadCheckOut = async () => {
  const { data } = await getRequest("/cart-service/api/cart/checkout");
  return data;
};

export const getCoupons = async () => {
  const { data } = await getRequest("/coupon-service/api/coupon/coupons");
  return data;
};

export const getReviews = async (productId) => {
  const { data } = await getRequest(`/user-service/api/review/reviews/${productId}`);
  return data;
};

// 좋아요는 토글 하나다. "좋아요/취소" 두 엔드포인트로 나누면 화면이 현재 상태를 알고
// 있어야 올바른 쪽을 부를 수 있는데, 다른 탭에서 이미 눌렀다면 그 판단이 틀린다.
// 서버가 방향을 정하고 { reviewId, likeCount, likedByMe } 로 최종 상태를 돌려준다.
export const toggleReviewLike = async (reviewId) => {
  const { data } = await postRequest(`/user-service/api/review/like/${reviewId}`, null);
  return data;
};

// ── 콘텐츠(배너·공지) ──────────────────────────────────────────────────────
//
// 공개 조회는 로그인이 필요 없다. 배너는 첫 화면이고 공지는 비회원도 봐야 한다.
// 관리 API 는 /admin 아래에 있고, 게이트웨이가 그 경로에만 인증을 건다.

// 첫 화면 구성. 섹션과 그 상품이 **그룹핑된 채로** 온다.
// 예전에는 /catalog-service/api/product/frontpage 를 불렀는데, 그건 캐시 워밍 함수를
// 그대로 노출한 것이라 모든 카테고리의 상품이 한 배열로 평탄화돼 왔고 프론트가
// filter 로 다시 나눴다. 무엇을 어떤 순서로 보여줄지도 여기 코드에 박혀 있었다.
export const getHome = async () => {
  const { data } = await getRequest("/catalog-service/api/content/home");
  return data;
};

export const adminListHomeSections = async () => {
  const { data } = await getRequest("/catalog-service/api/content/admin/home-sections");
  return data;
};

export const adminCreateHomeSection = async (section) => {
  const { data } = await postRequest("/catalog-service/api/content/admin/home-sections", section);
  return data;
};

export const adminUpdateHomeSection = async (id, section) => {
  const { data } = await putRequest(`/catalog-service/api/content/admin/home-sections/${id}`, section);
  return data;
};

export const adminDeleteHomeSection = async (id) =>
  deleteRequest(`/catalog-service/api/content/admin/home-sections/${id}`);

// 이미지 저장소가 쓰기 가능한지. 등록 화면이 폼을 그리기 전에 묻는다 —
// 자격 증명이 죽어 있으면 관리자는 긴 폼을 다 채우고 이미지를 올린 뒤에야 500 을 본다.
// 관리자 전용 경로(/api/content/admin) 아래에 있다: 버킷 이름과 SDK 오류 메시지가 실린다.
export const adminStorageHealth = async () => {
  const { data } = await getRequest("/catalog-service/api/content/admin/storage-health");
  return data;
};

export const adminProductImportStatus = async () => {
  const { data } = await getRequest("/catalog-service/api/content/admin/product-import");
  return data;
};

// 202 로 즉시 돌아온다(잡은 뒤에서 돈다).
//   409 = 이미 진행 중이거나 **카탈로그가 비어 있지 않다**(이 잡은 멱등하지 않다)
//   503 = 이미지 저장소에 쓸 수 없다
//
// reset=true 는 카탈로그를 비우고 시작한다 — 되돌릴 수 없다. 화면에서 두 번 묻는다.
export const adminStartProductImport = async (reset = false) => {
  const { data } = await postRequest(
    `/catalog-service/api/content/admin/product-import?reset=${reset ? "true" : "false"}`, {});
  return data;
};

// 홈 섹션 편집에 쓰는 카테고리 목록. 없는 카테고리를 가리키면 서버가 400 으로 막지만,
// 애초에 고르게 하는 편이 낫다.
// ── 내 프로필 ─────────────────────────────────────────────────────────────
//
// 🐛 예전에는 이 API 자체가 없었다. 화면은 JWT 페이로드로 사용자 객체를 만들어 썼는데
//    (util/jwtUser.js 의 toUser) 거기에는 { email, userId, roles } 뿐이다. 화면들이
//    user.name · user.image 를 읽으면 언제나 undefined 였다.
//
//    JWT 는 **신원 증명**이지 프로필 저장소가 아니다 — 이름을 바꿔도 이미 발급된 토큰은
//    그대로다. 프로필은 그때그때 서버에서 읽는다.

export const getMyProfile = async () => {
  const { data } = await getRequest("/user-service/api/user/profile/me");
  return data;
};

/** 표시 이름 변경. 이메일·로그인 id 는 바꿀 수 없다(서버가 받지 않는다). */
export const updateMyProfile = async ({ name }) => {
  const { data } = await putRequest("/user-service/api/user/profile/me", { name });
  return data;
};

/**
 * 프로필 이미지 업로드.
 *
 * <p>Content-Type 을 **지정하지 않는다.** multipart 는 경계 문자열(boundary)이 헤더에
 * 들어가야 하는데 그 값은 브라우저가 FormData 를 보면서 만든다. 여기서
 * "multipart/form-data" 를 직접 넣으면 boundary 가 빠져 서버가 본문을 파싱하지 못한다.
 */
export const uploadMyProfileImage = async (file) => {
  const form = new FormData();
  form.append("image", file);

  const { data } = await api.post("/user-service/api/user/profile/me/image", form);
  return data;
};

export const deleteMyProfileImage = async () => {
  const { data } = await deleteRequest("/user-service/api/user/profile/me/image");
  return data;
};

export const getCategories = async () => {
  const { data } = await getRequest("/catalog-service/api/category/categories");
  return data;
};

// 서브카테고리는 전부 받아 화면에서 카테고리로 거른다(항목이 수십 개 수준이고,
// 카테고리를 바꿀 때마다 왕복하면 선택이 한 박자 늦게 따라온다).
// 하나도 없으면 400 을 돌려주는 엔드포인트라, 빈 목록으로 받아 넘긴다.
export const getAllSubcategories = async () => {
  try {
    const { data } = await getRequest("/catalog-service/api/category/product/allsubcategories");
    return Array.isArray(data) ? data : [];
  } catch (e) {
    return [];
  }
};

/**
 * 관리자 상품 목록 — 커서 페이징.
 *
 * <p>예전 화면은 `/catalog-service/api/product/products` 를 불렀는데, 그것은 이름과 달리
 * 전 카테고리 × 전 서브카테고리를 돌며 **카탈로그 전체를 한 배열로** 돌려주고 그 결과를
 * Redis 키 하나에 담았다. 게다가 `/api/product/**` 는 공개 경로라 로그인하지 않아도
 * 전 카탈로그를 받아 갈 수 있었다.
 *
 * <p>관리자 전용 경로(`/api/content/admin/**`)여야 GET 도 ROLE_ADMIN 을 요구한다
 * (catalog-service 의 AdminWriteInterceptor 가 경로 접두사로 판정한다).
 *
 * @returns {{contents: Array, totalElements: number, nextCursor: number}}
 *          nextCursor 가 -1 이면 마지막 묶음이다.
 */
export const getAdminProducts = async ({ cursor = -1, pageSize = 20 } = {}) => {
  const { data } = await getRequest("/catalog-service/api/content/admin/products", {
    params: { cursor, pageSize },
  });
  return data;
};

export const getBanners = async () => {
  const { data } = await getRequest("/catalog-service/api/content/banners");
  return data;
};

export const getNotices = async ({ page = 0, size = 10 } = {}) => {
  const { data } = await getRequest("/catalog-service/api/content/notices", {
    params: { page, size },
  });
  return data;
};

export const getNotice = async (id) => {
  const { data } = await getRequest(`/catalog-service/api/content/notices/${id}`);
  return data;
};

export const adminListBanners = async () => {
  const { data } = await getRequest("/catalog-service/api/content/admin/banners");
  return data;
};

export const adminCreateBanner = async (banner) => {
  const { data } = await postRequest("/catalog-service/api/content/admin/banners", banner);
  return data;
};

export const adminUpdateBanner = async (id, banner) => {
  const { data } = await putRequest(`/catalog-service/api/content/admin/banners/${id}`, banner);
  return data;
};

export const adminDeleteBanner = async (id) =>
  deleteRequest(`/catalog-service/api/content/admin/banners/${id}`);

// ── 관리자 주문/환불 ───────────────────────────────────────────────────
//
// 목록은 고객과 무관하다 — 서버가 User-Role 로 관리자만 통과시킨다(403).
// status 는 OrderStatus 이름, trackingId 가 있으면 그 주문 하나만.
export const adminListOrders = async ({ status = "", trackingId = "", page = 0, size = 20 } = {}) => {
  const { data } = await getRequest("/order-service/api/order/admin/orders", {
    params: { status: status || undefined, trackingId: trackingId || undefined, page, size },
  });
  return data;
};

// 환불. 보내는 것은 어느 주문·왜 뿐이다 — 금액도 결제 참조도 본문에 없다.
// 관리자 여부는 서버가 User-Role 로 안다(같은 엔드포인트를 고객도 쓴다).
// items: [{orderProductId, qty}] — 비우면(null) 남은 전부. 금액은 어느 경우에도 보내지 않는다.
export const adminRefundOrder = async (trackingId, reason = "", items = null) => {
  const body = { trackingId, reason };
  if (items) body.items = items;
  const { data } = await postRequest("/order-service/api/order/refund", body);
  return data;
};

// ── 무통장입금 (docs/49 §11) — 결제 API 가 아니라 주문 상태 기계 ──
// 금액은 어디에도 없다. 입금액은 서버가 안내로 돌려주고, 확인은 관리자가 한다.
export const startBankTransfer = async (trackingId, depositorName) => {
  const { data } = await postRequest("/order-service/api/order/payment/bank-transfer/start", { trackingId, depositorName });
  return data;
};

export const getBankTransferNotice = async (trackingId) => {
  const { data } = await getRequest(`/order-service/api/order/payment/bank-transfer/${encodeURIComponent(trackingId)}`);
  return data;
};

export const cancelBankTransfer = async (trackingId) => {
  const { data } = await postRequest("/order-service/api/order/payment/bank-transfer/cancel", { trackingId });
  return data;
};

// 🔒 관리자만. 카드 결제 확인과 같은 무게 — 재고가 나가고 매출이 잡힌다. 금액은 보내지 않는다.
export const adminConfirmDeposit = async (trackingId, depositReference = "") => {
  const { data } = await postRequest("/order-service/api/order/admin/deposit-confirmed", { trackingId, depositReference });
  return data;
};

// 반품 입고. 보내는 것은 어느 주문·재고로 되돌릴지뿐이다 — 수량은 서버가 주문 품목에서 안다.
export const adminReceiveReturn = async (trackingId, restock = true, items = null) => {
  const body = { trackingId, restock };
  if (items) body.items = items;
  const { data } = await postRequest("/order-service/api/order/admin/return-received", body);
  return data;
};

export const adminListNotices = async ({ page = 0, size = 20 } = {}) => {
  const { data } = await getRequest("/catalog-service/api/content/admin/notices", {
    params: { page, size },
  });
  return data;
};

// 목록은 요약(제목·고정·공개)만 준다 — 본문 20개를 끌고 오면 안 된다. 수정하려면
// 본문이 필요하므로 상세를 따로 받는다.
export const adminGetNotice = async (id) => {
  const { data } = await getRequest(`/catalog-service/api/content/admin/notices/${id}`);
  return data;
};

export const adminCreateNotice = async (notice) => {
  const { data } = await postRequest("/catalog-service/api/content/admin/notices", notice);
  return data;
};

export const adminUpdateNotice = async (id, notice) => {
  const { data } = await putRequest(`/catalog-service/api/content/admin/notices/${id}`, notice);
  return data;
};

export const adminDeleteNotice = async (id) =>
  deleteRequest(`/catalog-service/api/content/admin/notices/${id}`);

// ── 이벤트 / 기획전 ────────────────────────────────────────────────────────
//
// 할인율은 기획전에만 저장되고 상품 조회 시 합성된다 — 상품의 상시 할인(ProductSku.discount)
// 을 덮어쓰지 않는다. 그래서 기획전을 지우면 가격이 원래대로 돌아오고, 진행 중에 관리자가
// 상품을 수정해도 종료 시 복원이 그 수정을 덮는 일이 없다.

/** 진행 중인 기획전. 초안과 예약분은 나오지 않는다. */
export const getEvents = async () => {
  const { data } = await getRequest("/catalog-service/api/content/events");
  return data;
};

/** 기획전 상세 — 기획전 정보와 대상 상품(할인율이 합성된 상태로 온다). */
export const getEvent = async (slug) => {
  const { data } = await getRequest(`/catalog-service/api/content/events/${slug}`);
  return data;
};

/** 관리자 목록 — 초안·예약·종료까지 전부. 안 보이는 것은 고칠 수 없다. */
export const adminListEvents = async () => {
  const { data } = await getRequest("/catalog-service/api/content/admin/events");
  return data;
};

export const adminGetEvent = async (id) => {
  const { data } = await getRequest(`/catalog-service/api/content/admin/events/${id}`);
  return data;
};

export const adminCreateEvent = async (event) => {
  const { data } = await postRequest("/catalog-service/api/content/admin/events", event);
  return data;
};

export const adminUpdateEvent = async (id, event) => {
  const { data } = await putRequest(`/catalog-service/api/content/admin/events/${id}`, event);
  return data;
};

/** 지금 끝낸다. 삭제와 다르다 — 진행 기록은 남는다. */
export const adminEndEvent = async (id) => {
  const { data } = await postRequest(`/catalog-service/api/content/admin/events/${id}/end`);
  return data;
};

export const adminDeleteEvent = async (id) =>
  deleteRequest(`/catalog-service/api/content/admin/events/${id}`);

/**
 * 기획전 쿠폰을 받아 간다.
 *
 * 받는 사람은 게이트웨이가 검증한 신원이다 — 이메일을 보내지 않는다. 자격(고객군) 판정은
 * 서버가 하고, 대상이 아니면 409 와 함께 이유를 준다.
 *
 * "이미 받았다"는 200 이다. 사용자 입장에서는 성공과 다르지 않고(버튼을 두 번 눌렀거나
 * 다른 탭에서 이미 받았다), 오류를 띄우면 무엇이 잘못됐는지 찾게 만든다.
 */
export const claimEventCoupon = async (slug) => {
  try {
    const { data } = await postRequest(`/coupon-service/api/coupon/events/${slug}/claim`);
    return { ok: true, ...data };
  } catch (error) {
    const body = error?.response?.data ?? {};
    return {
      ok: false,
      status: body.status ?? "UNAVAILABLE",
      message: body.message ?? translate("coupon_api.claim_failed"),
    };
  }
};

// ── 회원 탈퇴 ──────────────────────────────────────────────────────────────
// 신원은 보내지 않는다. 서버가 토큰에서 읽는다 — userId 를 받으면 남의 계정을 탈퇴시킬 수 있다.
export const getWithdrawalStatus = async () => {
  const { data } = await getRequest("/user-service/api/user/withdrawal");
  return data;
};

export const requestWithdrawal = async ({ password, reason }) => {
  const { data } = await postRequest("/user-service/api/user/withdrawal", { password, reason });
  return data;
};

// 지우는 것은 "탈퇴 신청"이지 계정이 아니다. 계정 삭제는 유예 기간 뒤 서버 배치가 한다.
export const cancelWithdrawal = async () => {
  const { data } = await deleteRequest("/user-service/api/user/withdrawal");
  return data;
};

// ── 로그인 기기 ────────────────────────────────────────────────────────────
//
// "새로운 기기에서 로그인되었습니다" 알림을 받은 사용자가 확인하러 오는 화면이 쓴다.
// 신원은 보내지 않는다 — 서버가 토큰에서 읽는다.

export const getMyDevices = async () => {
  const { data } = await getRequest("/user-service/api/user/profile/devices");
  return data;
};

/**
 * 내 등급과 그 근거.
 *
 * 신원은 보내지 않는다 — 서버가 토큰에서 읽는다. 등급은 그 사람의 소비 규모를 그대로
 * 드러내는 값이라 본인 것만 볼 수 있어야 한다.
 *
 * 기준 금액(실버/골드/VIP)도 서버가 내려준다. 화면에 하드코딩하면 설정을 바꾼 날부터
 * 안내가 거짓말이 된다.
 */
export const getMyTier = async () => {
  const { data } = await getRequest("/user-service/api/user/profile/tier");
  return data;
};

/**
 * 기기 기억을 지운다.
 *
 * ⚠️ 로그아웃이 아니다. 그 기기에 로그인된 상태가 있으면 그대로 남는다 — 뜻은 "다음에
 * 그 기기에서 로그인하면 새 기기로 보고 다시 알린다" 뿐이다.
 */
export const forgetDevice = async (deviceId) =>
  deleteRequest(`/user-service/api/user/profile/devices/${deviceId}`);

/**
 * 모든 기기에서 로그아웃 — 이 기기를 포함한 모든 로그인을 끊는다(user-service docs/68 §8).
 * 신원은 보내지 않는다 — 서버가 토큰에서 읽는다. 다른 기기는 최대 15분(액세스 토큰 수명) 안에 끊긴다.
 */
export const signOutEverywhere = async () => {
  const { data } = await postRequest("/user-service/api/user/profile/sessions/revoke-all");
  return data;
};

export const getAddresses = async () => {
  const { data } = await getRequest("/user-service/api/user/profile/address");
  return data;
};

// 결제 처리는 상태를 바꾸는 호출이다. GET 으로 보내면 중간 프록시·브라우저가 재시도하거나
// 캐시할 수 있고, 실제로 요청 본문(request)을 GET 의 config 자리에 넘기고 있어서 서버에
// 아무것도 전달되지 않았다.
export const processPayment = async (request) => {
  const { data } = await postRequest("/order-service/api/order/payment/process", request);
  return data;
};

export const createChatRoom = async ({ roomName }) => {
  const { data } = await postRequest("/chat-service/api/chat/room", { roomName });
  return data;
};

export const getChatRoomList = async () => {
  const { data } = await getRequest("/chat-service/api/chat/rooms");
  return data;
};

export const getRoomMessages = async (room, cursor) => {
  const { data } = await getRequest(`/chat-service/api/chat/room/${room?.roomId}`, {
    params: { cursor },
  });
  return data;
};

// ── 고객센터 채팅(docs/58) ──────────────────────────────────────────────
// 신원·역할은 서버가 토큰에서 읽는다 — 본문에 싣지 않는다.
const SUPPORT = "/chat-service/api/chat/support";

/** 열린 상담. 없으면 null(서버 204). */
export const getMySupportThread = async () => {
  const res = await getRequest(`${SUPPORT}/threads/mine`);
  return res.status === 204 || !res.data ? null : res.data;
};

/** 문의하기 — 열린 상담이 있으면 그것, 없으면 새로 연다. topic: ORDER|DELIVERY|PAYMENT|PRODUCT|ACCOUNT|OTHER. */
export const openSupportThread = async (topic) => {
  const { data } = await postRequest(`${SUPPORT}/threads/mine`, topic ? { topic } : {});
  return data;
};

/** 지난 상담(종료) — 최근 10개. */
export const getMySupportHistory = async () => {
  const { data } = await getRequest(`${SUPPORT}/threads/mine/history`);
  return Array.isArray(data) ? data : [];
};

/** 만족도 — 종료된 내 상담에 한 번. score 1~5, comment 500자까지. */
export const rateSupportThread = async (threadId, score, comment) => {
  const { data } = await postRequest(`${SUPPORT}/threads/${threadId}/rating`, { score, comment: comment || null });
  return data;
};

export const getSupportThread = async (threadId) => {
  const { data } = await getRequest(`${SUPPORT}/threads/${threadId}`);
  return data;
};

export const getSupportMessages = async (threadId, cursor = "-1") => {
  const { data } = await getRequest(`${SUPPORT}/threads/${threadId}/messages`, { params: { cursor } });
  return data;
};

export const closeSupportThread = async (threadId) => {
  const { data } = await postRequest(`${SUPPORT}/threads/${threadId}/close`, {});
  return data;
};

/** 상담 데스크 목록 — view: WAITING | MINE | ACTIVE | CLOSED, topic: 유형(없으면 전부). */
export const getSupportDesk = async (view, topic) => {
  const { data } = await getRequest(`${SUPPORT}/desk/threads`, { params: topic ? { view, topic } : { view } });
  return data;
};

/** 🔒 상담원 내부 메모 — 고객에게 보이지 않는다. */
export const updateSupportNote = async (threadId, note) => {
  const { data } = await putRequest(`${SUPPORT}/desk/threads/${threadId}/note`, { note });
  return data;
};

/** 맡은 상담 반납 — 다시 대기 목록으로. */
export const releaseSupportThread = async (threadId) => {
  const { data } = await postRequest(`${SUPPORT}/desk/threads/${threadId}/release`, {});
  return data;
};

export const claimSupportThread = async (threadId) => {
  const { data } = await postRequest(`${SUPPORT}/desk/threads/${threadId}/claim`, {});
  return data;
};

export default api;
