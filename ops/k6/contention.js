// 재고 경합 — 로그인한 데모 사용자 USERS 명이 재고 STOCK 개인 한 상품을 같은 순간에 주문한다. 실행: ./ops/53-load-k6.sh contention (docs/83 §2.3).
// [JA] 在庫競合 — ログインしたデモユーザー USERS 人が、在庫 STOCK 個の一つの商品を同じ瞬間に注文する。
//
// 보는 것: 받아진 주문(입금 안내까지 간 것)이 회차마다 STOCK 을 넘지 않는가 — 넘으면 초과 판매다(임계값).
//   재고 예약(docs/59)은 SKU 행을 잠그고 남은 수를 센다. 같은 SKU 에 주문이 몰리면 잠금을 기다리고, 기다림이 gRPC 기한(3초)을
//   넘으면 order 는 예약 없이 진행한다(fail-open — OrderStockHold.UNAVAILABLE). 읽기 위주의 browse · 손님마다 다른 상품을 사는
//   order 로는 이 길이 한 번도 열리지 않았다.
//
// 한 회차(ROUNDS 번):
//   준비 — 담기 정보 → 담기 → 체크아웃 저장 · 읽기 (회차 시작 전에, 몰리는 것은 주문부터)
//   시작 시각 — 모든 VU 가 함께: 주문(Idempotency-Key) → 주문 상세 → 무통장 입금 안내(입금 기한까지 다시 잡는다)
//   해제 시각(시작 + HOLD_SECONDS) — 받아진 주문의 입금을 함께 취소하고 줄을 뺀다 → GAP 초 뒤 다음 회차
// 해제 시각 뒤에 받아진 것은 "늦음" 으로 따로 센다 — 앞 손님들의 취소로 돌아온 재고라 초과 판매가 아니다.
//
// 재고는 시작 전에 호스트에서 맞춘다: ./ops/53-load-k6.sh stock set <수> [상품 id] — 그 명령이 이 시나리오의 실행 줄을 알려 준다.
// 계정 하나는 VU 하나(order 와 같다 — 리프레시 토큰이 회전한다). 로그인은 setup 에서 천천히.
import http from 'k6/http';
import { sleep, fail } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { GATEWAY, ok, json, authHeaders, loginSessions, refresh, errorThresholds, limitsRaisedOrFail, idempotencyKey,
  FALLBACK_ADDRESS } from './lib.js';
import { roundWindow, classify } from './contention-plan.js';

const USERS = Number(__ENV.USERS || 50);
const ID_OFFSET = Number(__ENV.ID_OFFSET || 0);
export const STOCK = Number(__ENV.STOCK || 0);
export const PRODUCT = String(__ENV.CONTENTION_PRODUCT || '');
export const ROUNDS = Number(__ENV.ROUNDS || 3);
const HOLD = Number(__ENV.HOLD_SECONDS || 20);
const GAP = Number(__ENV.ROUND_GAP_SECONDS || 10);

// 회차마다 — 받아짐 · 재고 부족 거절(주문 / 입금 안내) · 늦음 · 그 밖의 거절 · 오류
const accepted = new Counter('contention_accepted');
const shortCreate = new Counter('contention_short_create');
const shortStart = new Counter('contention_short_start');
const late = new Counter('contention_late');
const refused = new Counter('contention_refused');
const failed = new Counter('contention_errors');

const perRound = {};
for (let r = 0; r < ROUNDS; r++) {
  perRound[`contention_accepted{round:${r}}`] = [`count<=${STOCK}`];   // 초과 판매가 없다
  perRound[`contention_short_create{round:${r}}`] = ['count>=0'];
  perRound[`contention_late{round:${r}}`] = ['count>=0'];
}

// 재고 부족 거절(400)은 기대한 결과 — http_req_failed 에 넣지 않는다
const SHORT_OK = http.expectedStatuses(200, 400);

export const options = {
  setupTimeout: `${Math.ceil(USERS * 1.2) + 60}s`,
  scenarios: {
    contention: {
      executor: 'per-vu-iterations',
      exec: 'contention',
      vus: USERS,
      iterations: ROUNDS,
      maxDuration: `${ROUNDS * (HOLD + GAP) + 120}s`,
    },
  },
  thresholds: Object.assign(perRound, {
    contention_refused: ['count<1'],
    contention_errors: ['count<1'],
    contention_short_start: ['count>=0'],
    http_req_failed: ['rate<0.01'],
    not_json: ['count<1'],
  }, errorThresholds(['signin', 'token_refresh', 'cart_info', 'cart_add', 'cart_save', 'checkout', 'order_create', 'order_detail',
    'bank_transfer_start', 'bank_transfer_cancel', 'cart_remove'])),
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  if (!(STOCK > 0) || !/^[1-9][0-9]*$/.test(PRODUCT)) {
    fail('STOCK · CONTENTION_PRODUCT 가 필요하다 — 호스트에서 ./ops/53-load-k6.sh stock set <수> [상품 id] 가 실행 줄을 알려 준다');
  }
  if (USERS <= STOCK) {
    fail(`USERS(${USERS}) 가 STOCK(${STOCK}) 보다 많아야 경합이다`);
  }
  const password = __ENV.DEMO_PASSWORD;
  if (!password) {
    fail('DEMO_PASSWORD 가 없다 — ops/53 이 PASSWORD_FILE 에서 넘긴다');
  }
  limitsRaisedOrFail();
  const sessions = loginSessions(password, USERS, ID_OFFSET);
  // 담을 수 있는지 한 번 — SKU 하나 · 첫 사이즈(stock set 이 고른 상품)
  session = Object.assign({}, sessions[0]);
  const info = send('GET', `/catalog-service/api/cart/${PRODUCT}?style=0&size=0`, null, 'cart_info');
  if (info.status !== 200) {
    fail(`상품 ${PRODUCT} 의 담기 정보가 ${info.status} — 상품 id · 재고를 stock show 로 확인`);
  }
  // 모든 VU 가 로그인을 마친 뒤 같은 시각에 시작하도록 — 첫 회차는 10초 뒤
  return { sessions, t0: Date.now() + 10_000 };
}

let session = null;

function mySession(data) {
  if (!session || session.vu !== exec.vu.idInTest) {
    session = Object.assign({ vu: exec.vu.idInTest }, data.sessions[(exec.vu.idInTest - 1) % data.sessions.length]);
  }
  if (Date.now() - session.at > 13 * 60 * 1000 && !refresh(session)) {
    fail('토큰 갱신 실패 — 같은 계정을 두 VU 가 쓰고 있지 않은지');
  }
  return session;
}

function send(method, path, body, name, extra = {}, params = {}) {
  return http.request(method, `${GATEWAY}${path}`, body === null ? null : JSON.stringify(body),
    Object.assign({ headers: Object.assign(authHeaders(session.access), extra), tags: { name } }, params));
}

function waitUntil(ms) {
  const d = ms - Date.now();
  if (d > 0) {
    sleep(d / 1000);
  }
}

/** 주문 · 입금 안내 응답을 센다 — 재고 부족은 기대한 400, 그 밖의 400 · 5xx 는 시나리오 · 서버의 실패. */
function judge(res, name, round, shortCounter) {
  const kind = classify(res.status, res.body);
  const tags = { round: String(round) };
  if (kind === 'short') {
    ok(res, name, 400);
    shortCounter.add(1, tags);
  } else if (kind === 'refused') {
    ok(res, name);
    refused.add(1, tags);
  } else if (kind === 'error') {
    ok(res, name);
    failed.add(1, tags);
  } else {
    ok(res, name);
  }
  return kind;
}

export function contention(data) {
  mySession(data);
  const round = exec.vu.iterationInScenario;
  const w = roundWindow(data.t0, round, HOLD, GAP);

  // 준비 — 회차 시작 전에(몰리는 것은 주문부터)
  const infoRes = send('GET', `/catalog-service/api/cart/${PRODUCT}?style=0&size=0`, null, 'cart_info');
  if (!ok(infoRes, 'cart_info')) {
    failed.add(1, { round: String(round) });
    return;
  }
  const info = json(infoRes);
  const line = Object.assign({}, info, { qty: 1, _uid: `${PRODUCT}_0_0` });
  const addRes = send('PUT', '/cart-service/api/cart/items', line, 'cart_add');
  if (!ok(addRes, 'cart_add')) {
    failed.add(1, { round: String(round) });
    return;
  }
  const mine = (json(addRes) || []).filter((l) => String(l.id) === PRODUCT && String(l.size) === String(info.size));
  ok(send('POST', '/cart-service/api/cart/savecart', { products: mine.length ? mine : [line] }, 'cart_save'), 'cart_save');
  ok(send('GET', '/cart-service/api/cart/checkout', null, 'checkout'), 'checkout');

  // 시작 — 모든 VU 가 같은 순간에
  waitUntil(w.start);
  let trackingId = null;
  let awaiting = false;
  const createRes = send('POST', '/order-service/api/order/create',
    { shippingAddress: FALLBACK_ADDRESS, paymentMethod: 'cash', couponApplied: '' },
    'order_create', { 'Idempotency-Key': idempotencyKey() }, { responseCallback: SHORT_OK });
  if (judge(createRes, 'order_create', round, shortCreate) === 'ok') {
    const orderId = (json(createRes) || {}).orderId;
    const detail = send('GET', `/order-service/api/order/${encodeURIComponent(orderId)}`, null, 'order_detail');
    ok(detail, 'order_detail');
    trackingId = (json(detail) || {}).trackingId;
    if (trackingId) {
      const startRes = send('POST', '/order-service/api/order/payment/bank-transfer/start',
        { trackingId, depositorName: '경합시험' }, 'bank_transfer_start', {}, { responseCallback: SHORT_OK });
      if (judge(startRes, 'bank_transfer_start', round, shortStart) === 'ok') {
        awaiting = true;
        // 해제 시각 뒤에 받아진 것은 남이 돌려준 재고다 — 초과 판매로 세지 않는다
        (Date.now() <= w.release ? accepted : late).add(1, { round: String(round) });
      }
    }
  }

  // 해제 — 모두 함께. 입금을 취소해 예약을 풀고(입금 대기 한도 3건도 비운다) 줄을 뺀다
  waitUntil(w.release);
  if (awaiting) {
    ok(send('POST', '/order-service/api/order/payment/bank-transfer/cancel', { trackingId }, 'bank_transfer_cancel'),
      'bank_transfer_cancel');
  }
  const colorId = info.color && info.color.id;
  ok(send('DELETE', `/cart-service/api/cart/items?productId=${PRODUCT}&colorId=${encodeURIComponent(colorId)}`
    + `&size=${encodeURIComponent(info.size || '')}`, null, 'cart_remove'), 'cart_remove');
}

export default contention;
