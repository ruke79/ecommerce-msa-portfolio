// 조회 시나리오 — 비회원이 홈 → 카테고리 목록(1 · 2쪽) → 상품 상세를 본다. 실행: ./ops/53-load-k6.sh browse (docs/83 §2).
// [JA] 閲覧シナリオ — 非会員がホーム → カテゴリ一覧 → 商品詳細を見る。
//
// 도착률을 계단으로 올린다(ramping-arrival-rate) — 어느 단계에서 p95 · 오류가 꺾이는지가 결과다.
// ⚠️ 비로그인 요청은 IP 하나의 유입 제한 버킷(기본 초당 20)을 나눠 쓴다 — 데모에서는 모두 Traefik 파드 IP 로 보인다. 용량을 재려면
//    먼저 ./ops/53-load-k6.sh limits load (docs/83 §3). 그대로 두면 rate_limited 가 늘고 그것이 결과다.
//
// 데이터 규모 모드(PRODUCTS 를 주면, docs/83 §2.1) — ops/44 로 넣은 범위 전체에서 고른다:
//   · 상세는 그 범위의 무작위 상품 id(product_cold) — 홈에 나온 수십 개만 읽으면 늘 캐시에서 나와 데이터 크기가 보이지 않는다
//   · 목록은 1 ~ DEEP_PAGES 쪽 중 무작위(search_deep) — OFFSET 이 커질수록 비용이 는다
//   · NAME_SEARCH 비율만큼 이름 검색(name_search) — PostgreSQL 검색은 LIKE '%…%' 라 상품 수에 비례한다(docs/19 §6.1)
// [JA] データ規模モード — ops/44 で入れた範囲全体から選ぶ(詳細はランダムな id、一覧は深いページ、名前検索を一定割合)。
import { sleep, fail } from 'k6';
import { get, ok, homeOrFail, limitsRaisedOrFail, errorThresholds, purchasable, categories, pick, rate, stages } from './lib.js';

const PEAK = rate(100);

// ops/44 의 기본 ID_OFFSET(1000000)과 같다 — 생성기는 ID_OFFSET+1 ~ ID_OFFSET+PRODUCTS 에 넣는다(가드 LoadK6ScriptTest)
export const PRODUCT_ID_OFFSET = Number(__ENV.PRODUCT_ID_OFFSET || 1000000);
export const PRODUCTS = Number(__ENV.PRODUCTS || 0);
const SCALE = PRODUCTS > 0;
const DEEP_PAGES = Number(__ENV.DEEP_PAGES || (SCALE ? 50 : 2));
const NAME_SEARCH = Number(__ENV.NAME_SEARCH || (SCALE ? 0.2 : 0));
// 생성기(dept 분류)의 상품 이름에 들어가는 명사 — ops/tools/generate-catalog-data.py TAXONOMY
const NAME_TERMS = ['Hoodie', 'Laptop', 'Novel', 'Sofa', 'Earbuds', 'Backpack', 'Olive Oil', 'Sneakers', 'Blender', 'Serum',
  'Tablet', 'Desk', 'Cookbook', 'Jacket', 'Camera', 'Pillow'];

/** 데이터 규모 모드의 상품 id — 문자열(TSID 와 같이 브라우저로는 문자열). 상세는 숫자 id 로도 열린다(ProductController). */
export function coldProductId() {
  return String(PRODUCT_ID_OFFSET + 1 + Math.floor(Math.random() * PRODUCTS));
}

export const options = {
  scenarios: {
    browse: {
      executor: 'ramping-arrival-rate',
      startRate: Math.max(1, Math.round(PEAK / 10)),
      timeUnit: '1s',
      preAllocatedVUs: Math.max(10, PEAK),
      maxVUs: Math.max(50, PEAK * 4),
      stages: stages([
        { duration: '1m', target: Math.round(PEAK * 0.25) },
        { duration: '2m', target: Math.round(PEAK * 0.25) },
        { duration: '1m', target: Math.round(PEAK * 0.5) },
        { duration: '2m', target: Math.round(PEAK * 0.5) },
        { duration: '1m', target: PEAK },
        { duration: '2m', target: PEAK },
        { duration: '30s', target: 0 },
      ]),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:home}': ['p(95)<800'],
    'http_req_duration{name:search}': ['p(95)<1200'],
    'http_req_duration{name:product}': ['p(95)<800'],
    // 데이터 규모 모드에서만 나온다 — 상품 수마다 같은 도착률로 비교하는 것이 목적이라 임계값은 넉넉하게(넘으면 표에 적는다)
    'http_req_duration{name:product_cold}': ['p(95)<1500'],
    'http_req_duration{name:search_deep}': ['p(95)<2000'],
    'http_req_duration{name:name_search}': ['p(95)<3000'],
    checks: ['rate>0.99'],
    not_json: ['count<1'],
    ...errorThresholds(['home', 'banners', 'search', 'search_deep', 'name_search', 'product', 'product_cold']),
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

// 상품 · 카테고리 목록은 VU 마다 한 번 — 홈을 매번 다시 읽지만 고르는 목록은 처음 것으로(목록이 바뀌어도 상세는 slug 로 열린다).
let products = null;
let cats = null;

export function setup() {
  const home = homeOrFail(get('/catalog-service/api/content/home', { tags: { name: 'home' } }));
  limitsRaisedOrFail();   // 유입 제한을 올리지 않았으면 여기서 멈춘다(smoke 는 건너뜀)
  const list = purchasable(home);
  if (!list.length) {
    fail('홈에 담을 수 있는 상품이 없다 — 먼저 PRODUCTS=1000 PLACEHOLDER_IMAGE=/logo512.png ./ops/44-bulk-catalog.sh load');
  }
  if (SCALE) {
    // 범위의 처음과 끝이 실제로 있는가 — 없으면 상세가 전부 400 이라 "느리다" 가 아니라 "없다" 를 재게 된다
    for (const id of [PRODUCT_ID_OFFSET + 1, PRODUCT_ID_OFFSET + PRODUCTS]) {
      const res = get(`/catalog-service/api/product/${id}`, { tags: { name: 'product_cold' } });
      if (res.status !== 200) {
        fail(`상품 id ${id} 가 없다(${res.status}) — PRODUCTS · PRODUCT_ID_OFFSET 이 ops/44 로 넣은 범위와 같은지`
          + ` (PRODUCTS=${PRODUCTS} ./ops/44-bulk-catalog.sh status)`);
      }
    }
  }
  return { products: list, categories: categories(home) };
}

export function browse(data) {
  products = products || data.products;
  cats = cats || data.categories;

  ok(get('/catalog-service/api/content/home', { tags: { name: 'home' } }), 'home');
  ok(get('/catalog-service/api/content/banners', { tags: { name: 'banners' } }), 'banners');
  sleep(Math.random() * 0.5);

  if (cats.length) {
    const category = pick(cats);
    ok(get(`/catalog-service/api/search?category=${encodeURIComponent(category)}&page=1&pageSize=10&includeFilters=true`,
      { tags: { name: 'search' } }), 'search');
    if (SCALE) {
      const page = 2 + Math.floor(Math.random() * Math.max(1, DEEP_PAGES - 1));
      ok(get(`/catalog-service/api/search?category=${encodeURIComponent(category)}&page=${page}&pageSize=10`,
        { tags: { name: 'search_deep' } }), 'search_deep');
    } else {
      ok(get(`/catalog-service/api/search?category=${encodeURIComponent(category)}&page=2&pageSize=10`,
        { tags: { name: 'search' } }), 'search');
    }
    sleep(Math.random() * 0.5);
  }

  if (NAME_SEARCH > 0 && Math.random() < NAME_SEARCH) {
    ok(get(`/catalog-service/api/search?search=${encodeURIComponent(pick(NAME_TERMS))}&page=1&pageSize=10&includeFilters=true`,
      { tags: { name: 'name_search' } }), 'name_search');
  }

  if (SCALE) {
    ok(get(`/catalog-service/api/product/${coldProductId()}`, { tags: { name: 'product_cold' } }), 'product_cold');
  } else {
    const p = pick(products);
    ok(get(`/catalog-service/api/product/${encodeURIComponent(p.slug)}`, { tags: { name: 'product' } }), 'product');
  }
}

export default browse;
