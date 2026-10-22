// 쓰기가 섞인 조회 — browse 를 그대로 돌리면서, 로그인한 데모 사용자가 초당 REVIEW_RATE 번 리뷰를 쓴다. 실행: ./ops/53-load-k6.sh mixed (docs/83 §2.2).
// [JA] 書き込みが混ざる閲覧 — browse をそのまま回しつつ、ログインしたデモユーザーが毎秒 REVIEW_RATE 回レビューを書く。
//
// 왜 리뷰인가: 리뷰를 쓰거나 고칠 때마다 user-service 가 그 상품의 평균 별점을 catalog 에 보내고(ReviewService.publishRating →
// PUT /api/product/rating), catalog 의 ProductService.updateRating 은 search_cache 와 필터 메뉴 캐시(colors · sizes · details ·
// brands)를 **통째로** 비웠다(@CacheEvict allEntries) — 2026-10-19 리뷰 초당 1건에 실패 79.90%, 그 상품의 캐시만 맞추게 고친 뒤 0.02%
// (docs/83 §5). 읽기만 하는 browse 는 이 경로를 한 번도 지나지 않는다 — mixed 는 그것이 다시 넓어지지 않는지 본다.
//
// 데모 계정은 받은 상품이 없어 리뷰가 403(받은 상품만 — review.require-purchase)이다. 측정하는 동안만 그 확인을 끈다:
// 호스트에서 ./ops/53-load-k6.sh reviews open → 끝나면 reviews default. 시작할 때 리뷰 하나를 써 보고 403 이면 멈춘다.
// 같은 사람이 같은 상품에 다시 쓰면 고치기다(리뷰는 사람 · 상품마다 하나) — 그때도 별점을 다시 보낸다. 본문은 'k6 리뷰' 로 시작한다.
//
// 로그인은 order 와 같다(setup 에서 리뷰 VU 수만큼, 천천히). VU 번호는 browse VU 와 함께 매겨져 두 리뷰 VU 가 같은 계정을 고를 수
// 있다 — 문제는 13분 뒤의 토큰 갱신뿐이라(리프레시 토큰이 회전한다), 갱신이 실패하면 그 계정으로 다시 로그인한다.
import http from 'k6/http';
import { fail } from 'k6';
import exec from 'k6/execution';
import { GATEWAY, ok, authHeaders, login, loginSessions, refresh, errorThresholds, pick, demoEmail } from './lib.js';
import { browse, setup as browseSetup, options as browseOptions, coldProductId, PRODUCTS } from './browse.js';
import { totalSeconds } from './durations.js';

const USERS = Number(__ENV.USERS || 100);
const ID_OFFSET = Number(__ENV.ID_OFFSET || 0);
export const REVIEW_RATE = Number(__ENV.REVIEW_RATE || 1);
const REVIEW_VUS = Math.max(1, Math.min(USERS, Math.ceil(REVIEW_RATE * 5)));
const BROWSE_NAMES = ['home', 'banners', 'search', 'search_deep', 'name_search', 'filter_search', 'product', 'product_cold'];

const browseScenario = browseOptions.scenarios.browse;

export const options = {
  setupTimeout: `${Math.ceil(USERS * 1.2) + 60}s`,
  scenarios: {
    browse: Object.assign({}, browseScenario, { exec: 'browse' }),
    reviews: {
      executor: 'constant-arrival-rate',
      exec: 'review',
      rate: REVIEW_RATE,
      timeUnit: '1s',
      duration: `${totalSeconds(browseScenario.stages)}s`,
      preAllocatedVUs: REVIEW_VUS,
      maxVUs: REVIEW_VUS,
    },
  },
  thresholds: Object.assign({}, browseOptions.thresholds, {
    'http_req_duration{name:review_add}': ['p(95)<3000'],
    not_json: ['count<1'],
  }, errorThresholds([...BROWSE_NAMES, 'review_add', 'signin', 'token_refresh'])),
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  if (!(REVIEW_RATE > 0)) {
    fail(`REVIEW_RATE 는 양수 — ${__ENV.REVIEW_RATE}`);
  }
  if (USERS < REVIEW_VUS) {
    fail(`USERS(${USERS}) 가 리뷰 VU(${REVIEW_VUS}) 보다 적다 — 계정 하나는 VU 하나`);
  }
  const password = __ENV.DEMO_PASSWORD;
  if (!password) {
    fail('DEMO_PASSWORD 가 없다 — ops/53 이 PASSWORD_FILE 에서 넘긴다');
  }
  const data = browseSetup();
  const sessions = loginSessions(password, REVIEW_VUS, ID_OFFSET);
  // 시작 전 확인 — 리뷰를 쓸 수 있는가(받은 상품만 확인이 켜져 있으면 403)
  const probe = writeReview(sessions[0], reviewTarget(data));
  if (probe.status === 403) {
    fail('리뷰가 403 이다(받은 상품만) — 호스트에서 ./ops/53-load-k6.sh reviews open 후 다시(끝나면 reviews default)');
  }
  if (probe.status !== 200) {
    fail(`리뷰 쓰기 확인이 ${probe.status} — ${String(probe.body).slice(0, 200)}`);
  }
  return Object.assign({}, data, { sessions });
}

export { browse };

/** 리뷰를 쓸 상품 — 데이터 규모 모드면 ops/44 범위의 무작위 상품, 아니면 홈의 상품. */
function reviewTarget(data) {
  return PRODUCTS > 0 ? coldProductId() : String(pick(data.products).id);
}

/** 화면과 같은 모양(Review.jsx) — multipart, 부분 'review' 는 JSON. 사이즈 없는 상품처럼 사이즈 · 착용감은 비운다. */
function writeReview(session, productId) {
  const body = {
    review: `k6 리뷰 ${Date.now()}`,
    rating: 0.5 * (1 + Math.floor(Math.random() * 10)),
    size: '',
    fit: '',
    style: { color: 'k6', image: '' },
  };
  const headers = Object.assign({}, authHeaders(session.access));
  delete headers['Content-Type'];   // k6 가 multipart 경계를 붙인다
  const res = http.post(`${GATEWAY}/user-service/api/review/add/${encodeURIComponent(productId)}`,
    { review: http.file(JSON.stringify(body), 'review.json', 'application/json') },
    { headers, tags: { name: 'review_add' } });
  ok(res, 'review_add');
  return res;
}

let session = null;

let account = 0;

/** 리뷰 VU 마다의 세션 — setup 의 세션 하나에서 시작해 13분이 지나면 갱신하고, 갱신이 실패하면(다른 VU 가 먼저 회전시켰다) 다시 로그인한다. */
function mySession(data) {
  if (!session) {
    account = (exec.vu.idInTest - 1) % data.sessions.length;
    session = Object.assign({}, data.sessions[account]);
  }
  if (Date.now() - session.at > 13 * 60 * 1000 && !refresh(session)) {
    const again = login(demoEmail(ID_OFFSET + account), __ENV.DEMO_PASSWORD);
    if (again.error) {
      fail(`다시 로그인 실패 ${demoEmail(ID_OFFSET + account)}: ${again.error}`);
    }
    session = again;
  }
  return session;
}

export function review(data) {
  writeReview(mySession(data), reviewTarget(data));
}

export default browse;
