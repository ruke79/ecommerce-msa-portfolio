// 주문 시나리오 — 로그인한 데모 사용자가 상품을 보고 장바구니에 담아 무통장 입금으로 주문한다. 실행: ./ops/53-load-k6.sh order (docs/83 §2).
// [JA] 注文シナリオ — ログインしたデモユーザーが商品を見てカートに入れ、銀行振込で注文する。
//
// 한 반복 = 화면의 한 번 구매:
//   상품 상세 → 담기 정보(catalog) → 담기(cart) → 체크아웃 저장 → 체크아웃 · 배송지 읽기 → 주문(Idempotency-Key)
//   → 주문 상세(trackingId) → 무통장 입금 안내 → (기본) 입금 취소 · 줄 빼기
// 쓰기 경로가 다 지난다 — 재고 예약(gRPC) · 주문 아웃박스 · Kafka · 장바구니 캐시.
//
// 끝에 입금을 취소하는 이유: 입금 대기 주문은 손님당 3건까지(bank-transfer.max-pending-per-customer)이고 재고를 49시간 잡는다 —
// 그대로 두면 같은 계정의 4번째 반복부터 400 이고, 몇 번 돌리면 재고가 바닥난다. KEEP_ORDERS=1 이면 취소하지 않는다(한 번만 볼 때).
//
// 로그인은 setup() 에서 한 번씩, 천천히(로그인 유입 제한 초당 1 · 버스트 10 — 바꿀 수 없다). 계정 하나는 VU 하나만 쓴다:
// 리프레시 토큰이 회전해서 둘이 같이 갱신하면 늦은 쪽이 그 로그인을 끝낸다(RefreshTokenController). 그래서 maxVUs = USERS.
import http from 'k6/http';
import { sleep, fail } from 'k6';
import exec from 'k6/execution';
import { Trend } from 'k6/metrics';
import { GATEWAY, ok, json, homeOrFail, authHeaders, demoEmail, login, refresh, purchasable, categories, pick, rate, stages,
  idempotencyKey, FALLBACK_ADDRESS, get } from './lib.js';

const USERS = Number(__ENV.USERS || 100);
const ID_OFFSET = Number(__ENV.ID_OFFSET || 0);
const PEAK = rate(Math.min(10, USERS));
const KEEP = __ENV.KEEP_ORDERS === '1';

// 한 번 구매 전체의 시간 — 요청 하나하나가 아니라 손님이 느끼는 것
const purchase = new Trend('purchase_duration', true);

export const options = {
  setupTimeout: `${Math.ceil(USERS * 1.2) + 60}s`,
  scenarios: {
    order: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: Math.min(USERS, Math.max(5, PEAK * 3)),
      maxVUs: USERS,
      stages: stages([
        { duration: '1m', target: Math.max(1, Math.round(PEAK * 0.3)) },
        { duration: '2m', target: Math.max(1, Math.round(PEAK * 0.3)) },
        { duration: '1m', target: PEAK },
        { duration: '3m', target: PEAK },
        { duration: '30s', target: 0 },
      ]),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:order_create}': ['p(95)<2000'],
    'http_req_duration{name:cart_add}': ['p(95)<1000'],
    purchase_duration: ['p(95)<6000'],
    checks: ['rate>0.99'],
    not_json: ['count<1'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const password = __ENV.DEMO_PASSWORD;
  if (!password) {
    fail('DEMO_PASSWORD 가 없다 — ops/53 이 PASSWORD_FILE 에서 넘긴다');
  }
  const home = homeOrFail(get('/catalog-service/api/content/home', { tags: { name: 'home' } }));
  const products = purchasable(home);
  if (!products.length) {
    fail('담을 수 있는 상품이 없다(SKU 하나 · 재고 5 이상) — 먼저 ops/44 load');
  }

  const sessions = [];
  for (let i = 0; i < USERS; i++) {
    // 버스트 10 을 다 쓴 뒤로는 초당 1 보다 조금 느리게 — 429 를 받으면 기다렸다가 같은 계정을 다시
    if (i >= 9) {
      sleep(1.1);
    }
    let s = login(demoEmail(ID_OFFSET + i), password);
    for (let tries = 0; s.error && s.error.startsWith('429') && tries < 5; tries++) {
      sleep(2);
      s = login(demoEmail(ID_OFFSET + i), password);
    }
    if (s.error) {
      fail(`로그인 실패 ${demoEmail(ID_OFFSET + i)}: ${s.error} — 계정이 있는지(ops/48 status) · 비밀번호 파일`);
    }
    sessions.push(s);
  }
  return { products, sessions, categories: categories(home) };
}

// VU 마다의 세션 — setup 이 돌려준 것의 사본에서 시작해 이 VU 가 갱신한다
let session = null;

function mySession(data) {
  if (!session) {
    const idx = (exec.vu.idInTest - 1) % data.sessions.length;
    session = Object.assign({}, data.sessions[idx]);
  }
  // 액세스 토큰 15분 — 13분이 지나면 갱신(화면은 만료 10초 전)
  if (Date.now() - session.at > 13 * 60 * 1000) {
    if (!refresh(session)) {
      fail('토큰 갱신 실패 — 같은 계정을 두 VU 가 쓰고 있지 않은지(USERS ≥ maxVUs)');
    }
  }
  return session;
}

function send(method, path, body, name, extra = {}) {
  const params = { headers: Object.assign(authHeaders(session.access), extra), tags: { name } };
  return http.request(method, `${GATEWAY}${path}`, body === null ? null : JSON.stringify(body), params);
}

export function order(data) {
  mySession(data);
  const started = Date.now();
  const p = pick(data.products);

  // 상품 상세 → 담기 정보
  ok(send('GET', `/catalog-service/api/product/${encodeURIComponent(p.slug)}`, null, 'product'), 'product');
  sleep(Math.random());
  const infoRes = send('GET', `/catalog-service/api/cart/${encodeURIComponent(p.id)}?style=${p.style}&size=${p.size}`, null, 'cart_info');
  if (!ok(infoRes, 'cart_info')) {
    return;
  }
  const info = json(infoRes);
  const line = Object.assign({}, info, { qty: 1, _uid: `${p.id}_${p.style}_${p.size}` });

  // 담기 — 응답은 장바구니 전체
  const addRes = send('PUT', '/cart-service/api/cart/items', line, 'cart_add');
  if (!ok(addRes, 'cart_add')) {
    return;
  }
  const cart = json(addRes) || [];
  const mine = cart.filter((l) => String(l.id) === String(p.id) && String(l.size) === String(info.size));

  // 체크아웃 버튼 — 고른 줄만 저장(나머지는 선택 해제)
  ok(send('POST', '/cart-service/api/cart/savecart', { products: mine.length ? mine : [line] }, 'cart_save'), 'cart_save');
  ok(send('GET', '/cart-service/api/cart/checkout', null, 'checkout'), 'checkout');
  const addrRes = send('GET', '/user-service/api/user/profile/address', null, 'address');
  ok(addrRes, 'address');
  const saved = (json(addrRes) || []).find((a) => a && a.active) || null;
  sleep(Math.random());

  // 주문 — 화면처럼 시도마다 새 멱등 키, 무통장 입금은 paymentMethod "cash"
  const createRes = send('POST', '/order-service/api/order/create',
    { shippingAddress: saved || FALLBACK_ADDRESS, paymentMethod: 'cash', couponApplied: '' },
    'order_create', { 'Idempotency-Key': idempotencyKey() });
  if (!ok(createRes, 'order_create')) {
    return;
  }
  const orderId = (json(createRes) || {}).orderId;
  const detailRes = send('GET', `/order-service/api/order/${encodeURIComponent(orderId)}`, null, 'order_detail');
  ok(detailRes, 'order_detail');
  const trackingId = (json(detailRes) || {}).trackingId;
  if (!trackingId) {
    return;
  }

  ok(send('POST', '/order-service/api/order/payment/bank-transfer/start',
    { trackingId, depositorName: '부하시험' }, 'bank_transfer_start'), 'bank_transfer_start');
  purchase.add(Date.now() - started);

  if (!KEEP) {
    // 손님이 입금하지 않고 취소 — 재고 예약을 풀고 입금 대기 한도(3건)를 비운다. 담은 줄도 뺀다(장바구니가 끝없이 길어지지 않게).
    ok(send('POST', '/order-service/api/order/payment/bank-transfer/cancel', { trackingId }, 'bank_transfer_cancel'),
      'bank_transfer_cancel');
    const colorId = info.color && info.color.id;
    ok(send('DELETE', `/cart-service/api/cart/items?productId=${encodeURIComponent(p.id)}&colorId=${encodeURIComponent(colorId)}`
      + `&size=${encodeURIComponent(info.size || '')}`, null, 'cart_remove'), 'cart_remove');
  }
}

export default order;
