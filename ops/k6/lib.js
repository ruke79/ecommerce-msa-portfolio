// k6 시나리오 공통 — 화면(frontend/src/util/api.js)과 같은 주소 · 같은 헤더. 실행은 ops/53-load-k6.sh (docs/83).
// [JA] k6 シナリオ共通 — 画面と同じアドレス・同じヘッダー。実行は ops/53-load-k6.sh。
//
// 응답이 JSON 인지 늘 본다 — 🐛 데모 인그레스가 API 를 프론트로 보내던 때(2026-10-17 전)는 모든 API 가 index.html 200 이라
// 상태 코드만 보는 검사는 전부 "성공" 이었다.
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { clientIp } from './clients.js';

export const GATEWAY = (__ENV.GATEWAY || 'https://shop.lab').replace(/\/+$/, '');

// 유입 제한(429)은 실패와 따로 센다 — 제한이 일한 것인지 서버가 못 버틴 것인지 나눠 읽는다(docs/19 §4).
// [JA] 流入制限(429)は失敗と別に数える — 制限が働いたのかサーバーが耐えられなかったのかを分けて読む(docs/19 §4)。
export const rateLimited = new Counter('rate_limited');
export const notJson = new Counter('not_json');
// 429 가 아닌 실패를 종류별로 센다 — 🐛 2026-10-18: 요약에 http_req_failed 비율만 남아 cart_add 의 실패 464건 · browse 의 1.06% 가
// 5xx 인지(서버) 4xx 인지(데이터 · 요청) 시간 초과인지 알 수 없었다. 엔드포인트별 값은 errorThresholds 가 요약에 남긴다.
// [JA] 429 以外の失敗を種類別に数える — 🐛 2026-10-18: 要約に http_req_failed の比率だけが残り、cart_add の失敗 464 件・browse の 1.06% が
//      5xx(サーバー)か 4xx(データ・要求)かタイムアウトか分からなかった。エンドポイント別の値は errorThresholds が要約に残す。
// [JA] 429 以外の失敗を種類別に数える(5xx · 4xx · 応答なし)。
export const errors5xx = new Counter('errors_5xx');
export const errors4xx = new Counter('errors_4xx');
export const noResponse = new Counter('errors_no_response');

/**
 * 엔드포인트별 오류 수를 요약(--summary-export)에 남기는 임계값 — 언제나 통과한다(count>=0). k6 는 임계값을 건 부분 지표만 요약에 쓴다.
 * 시나리오의 thresholds 에 펼쳐 넣는다: { ..., ...errorThresholds(['home', ...]) }.
 *
 * <p>[JA] エンドポイント別のエラー数を要約(--summary-export)に残す閾値 — 常に通過する(count>=0)。k6 は閾値をかけたサブ指標だけを要約に書く。
 * シナリオの thresholds に展開して入れる: { ..., ...errorThresholds(['home', ...]) }。
 */
export function errorThresholds(names) {
  const t = {};
  for (const n of names) {
    t[`errors_5xx{name:${n}}`] = ['count>=0'];
    t[`errors_4xx{name:${n}}`] = ['count>=0'];
  }
  return t;
}

export const BASE_HEADERS = {
  'Content-Type': 'application/json',
  Accept: 'application/json',
  MultiLogin: 'false',
  'Accept-Language': 'ko',
};

// 손님 IP 흉내(docs/83 §2.6) — CLIENT_IPS 개를 VU 마다 돌려 쓴다. 다른 부하 VM 과는 CLIENT_IP_OFFSET 으로 겹치지 않는다(ops/53 SPLIT).
// [JA] 客の IP の模倣(docs/83 §2.6) — CLIENT_IPS 個を VU ごとに使い回す。他の負荷 VM とは CLIENT_IP_OFFSET で重ならない(ops/53 SPLIT)。
export const CLIENT_IPS = Number(__ENV.CLIENT_IPS || 0);
const CLIENT_IP_OFFSET = Number(__ENV.CLIENT_IP_OFFSET || 0);

/** 이 VU 의 요청 머리 — 손님 IP 를 흉내 내면 CF-Connecting-IP 를 더한다(setup 은 VU 0 → 첫 주소). */
// [JA] この VU のリクエストヘッダー — 客の IP を模倣するなら CF-Connecting-IP を足す(setup は VU 0 → 最初のアドレス)。
export function baseHeaders() {
  const ip = clientIp(exec.vu.idInTest, CLIENT_IPS, CLIENT_IP_OFFSET);
  return ip ? Object.assign({}, BASE_HEADERS, { 'CF-Connecting-IP': ip }) : BASE_HEADERS;
}

/** 응답 하나를 판정한다 — 기대 상태 · JSON. 429 는 rate_limited 로. */
// [JA] 応答 1 つを判定する — 期待ステータス · JSON。429 は rate_limited へ。
export function ok(res, name, expected = 200) {
  if (res.status === 429) {
    rateLimited.add(1, { name });
  } else if (res.status === 0) {
    noResponse.add(1, { name });
  } else if (res.status >= 500) {
    errors5xx.add(1, { name });
  } else if (res.status >= 400 && res.status !== expected) {
    errors4xx.add(1, { name });
  }
  const type = String(res.headers['Content-Type'] || '');
  const isJson = type.includes('application/json');
  if (res.status === expected && res.body && String(res.body).length > 0 && !isJson) {
    notJson.add(1, { name });
  }
  return check(res, {
    [`${name} ${expected}`]: (r) => r.status === expected,
    [`${name} json`]: () => expected === 204 || !res.body || String(res.body).length === 0 || isJson,
  });
}

/**
 * 시작 전 확인 — 홈이 JSON 으로 오는가. HTML 이면 API 가 게이트웨이가 아니라 프론트(nginx 의 index.html)로 갔다:
 * 데모 인그레스에 그 경로가 없다 — 데이터 문제가 아니다.
 *
 * <p>[JA] 開始前の確認 — ホームが JSON で来るか。HTML なら API がゲートウェイではなくフロント(nginx の index.html)に行った:
 * デモのイングレスにそのパスがない — データの問題ではない。
 */
export function homeOrFail(res) {
  const type = String(res.headers['Content-Type'] || '');
  if (res.status !== 200 || !type.includes('application/json')) {
    fail(`홈 응답이 ${res.status} ${type || '(형식 없음)'} — JSON 이 아니면 API 가 게이트웨이로 가지 않는다(`
      + '게이트웨이 인그레스가 배포됐는지). 429 면 유입 제한 — 잠시 뒤 다시');
  }
  return res.json();
}

/**
 * 시작 전 확인 — 게이트웨이 유입 제한을 올렸는가(ops/53 limits load). 비로그인 요청 60개를 한꺼번에 보내 429 가 하나라도 나오면 멈춘다.
 * 🐛 2026-10-18 첫 측정: 제한이 기본값(초당 20 · 버스트 40)인 채 browse · order · spike 를 돌려, 비로그인 요청이 모두 Traefik IP
 *    하나의 버킷을 나눠 썼다 — catalog 가 받은 요청은 정확히 초당 20 이었고 browse 실패 138,046건 중 137,981건이 429 였다.
 *    서비스의 용량이 아니라 제한값을 잰 결과라 모두 버렸다. smoke 는 제한을 올리기 전에 도는 점검이라 건너뛴다.
 *    제한 자체를 재려면 ALLOW_RATE_LIMIT=1.
 * [JA] 開始前の確認 — ゲートウェイの流入制限を上げたか(ops/53 limits load)。非ログインの要求 60 個を一度に送り、429 が一つでも出たら止まる。
 *      🐛 2026-10-18 最初の計測: 制限が既定値(毎秒 20・バースト 40)のまま browse・order・spike を回し、非ログインの要求がすべて Traefik の
 *      IP 一つのバケットを分け合った — catalog が受けた要求は正確に毎秒 20 で、browse の失敗 138,046 件のうち 137,981 件が 429 だった。
 *      サービスの容量ではなく制限値を測った結果なので、すべて捨てた。smoke は制限を上げる前に回す点検なので飛ばす。
 *      制限そのものを測るには ALLOW_RATE_LIMIT=1。
 * [JA] 開始前に流入制限が上がっているかを確かめる。既定値のまま計測して 429 だけを測った(2026-10-18)。
 */
export function limitsRaisedOrFail() {
  // 손님 IP 를 흉내 내면 제한은 손님마다다 — 기본 제한 그대로 재는 것이 목적이라 확인하지 않는다(docs/83 §2.6)
  // [JA] 客の IP を模倣するなら制限は客ごとだ — 既定の制限のまま測るのが目的なので確認しない(docs/83 §2.6)
  if (__ENV.SCENARIO === 'smoke' || __ENV.ALLOW_RATE_LIMIT === '1' || CLIENT_IPS > 0) {
    return;
  }
  const reqs = [];
  for (let i = 0; i < 60; i++) {
    reqs.push(['GET', `${GATEWAY}/catalog-service/api/content/banners`, null, { headers: BASE_HEADERS, tags: { name: 'limit_probe' } }]);
  }
  const limited = http.batch(reqs).filter((r) => r.status === 429).length;
  if (limited > 0) {
    fail(`게이트웨이 유입 제한이 기본값으로 보인다 — 비로그인 요청 60개 중 ${limited}개가 429. `
      + '호스트에서 ./ops/53-load-k6.sh limits load 후 limits status 로 확인하고 다시(docs/83 §3). 제한 자체를 재려면 ALLOW_RATE_LIMIT=1');
  }
}

export function json(res) {
  try {
    return res.json();
  } catch (e) {
    return null;
  }
}

/** 인증 헤더 — 게이트웨이는 Bearer 를 먼저 본다(JwtAuthorizationGatewayFilterFactory). */
// [JA] 認証ヘッダー — ゲートウェイは Bearer を先に見る(JwtAuthorizationGatewayFilterFactory)。
export function authHeaders(access) {
  return Object.assign({}, baseHeaders(), { Authorization: `Bearer ${access}` });
}

export function get(path, params = {}) {
  return http.get(`${GATEWAY}${path}`, Object.assign({ headers: baseHeaders() }, params));
}

/**
 * 시작 확인용 GET — 응답이 status 0(연결 자체가 안 됨)이면 잠깐 기다려 다시 한다(기본 30초).
 * 🐛 2026-11-21 운영 첫 천장 측정: 클러스터 안 k6 Job 파드의 **첫 요청이 거부**됐다(1ms · connection refused) — k3s 의 네트워크 정책
 *    (kube-router)이 새 파드를 '같은 네임스페이스' 허용 집합에 넣기까지 몇 초 걸린다(5초 뒤부터 200). setup() 이 그 한 번으로 멈춰
 *    측정이 통째로 돌지 않았다. 0 밖의 상태(429 · 5xx · 4xx)는 다시 하지 않는다 — 그건 진짜 응답이다.
 * [JA] 開始確認用の GET — status 0(接続不可)なら少し待って再試行。新しい Pod はネットワークポリシーに入るまで数秒かかる(2026-11-21)。
 */
export function getWhenReachable(path, params = {}, seconds = 30) {
  let res = get(path, params);
  for (let waited = 0; res.status === 0 && waited < seconds; waited += 2) {
    sleep(2);
    res = get(path, params);
  }
  return res;
}

/** 데모 계정 이메일 — ops/48 과 같은 번호(7자리, 0부터). */
// [JA] デモアカウントのメール — ops/48 と同じ番号(7 桁、0 から)。
export function demoEmail(n) {
  return `demo${String(n).padStart(7, '0')}@demo.invalid`;
}

/** 쿠키 값 하나(Set-Cookie 에서). */
// [JA] クッキーの値 1 つ(Set-Cookie から)。
function cookieValue(res, name) {
  const c = res.cookies && res.cookies[name];
  return c && c.length ? c[0].value : null;
}

/**
 * 로그인 — 액세스 토큰은 응답 헤더 ACCESS, 리프레시는 REFRESH 쿠키(AuthLoginFilter). 2FA · 위험 확인 응답은 실패로 본다.
 * 쿠키 항아리는 쓰지 않는다 — setup() 에서 받은 토큰을 VU 마다 나눠 들고, 갱신은 그 VU 가 자기 쿠키로 한다.
 *
 * <p>[JA] ログイン — アクセストークンは応答ヘッダー ACCESS、リフレッシュは REFRESH クッキー(AuthLoginFilter)。2FA · リスク確認の応答は失敗とみなす。
 * クッキージャーは使わない — setup() で受け取ったトークンを VU ごとに分けて持ち、更新はその VU が自分のクッキーで行う。
 */
export function login(email, password) {
  const res = http.post(`${GATEWAY}/user-service/api/auth/public/signin`, JSON.stringify({ email, password }),
    { headers: baseHeaders(), jar: new http.CookieJar(), tags: { name: 'signin' } });
  ok(res, 'signin');
  const access = res.headers['Access'] || res.headers['ACCESS'] || res.headers['access'];
  const body = json(res) || {};
  if (res.status !== 200 || !access) {
    return { error: `${res.status} ${body.code || ''}`.trim() };
  }
  return { access, refresh: cookieValue(res, 'REFRESH'), device: cookieValue(res, 'DEVICE'), at: Date.now() };
}

/**
 * 데모 계정 로그인 — setup() 에서 계정마다 한 번, 천천히(로그인 유입 제한 초당 1 · 버스트 10 — 바꿀 수 없다). 429 면 기다렸다가 같은 계정을
 * 다시. 계정 하나는 VU 하나만 쓴다 — 리프레시 토큰이 회전해서 둘이 같이 갱신하면 늦은 쪽이 그 로그인을 끝낸다(RefreshTokenController).
 *
 * <p>[JA] デモアカウントのログイン — setup() でアカウントごとに一度、ゆっくり(ログインの流入制限は毎秒 1 · バースト 10 — 変えられない)。429 なら待って同じアカウントで
 * 再試行。1 アカウントは 1 VU だけが使う — リフレッシュトークンがローテーションするので、2 つが同時に更新すると遅い方がそのログインを終わらせる(RefreshTokenController)。
 */
export function loginSessions(password, users, offset) {
  const sessions = [];
  for (let i = 0; i < users; i++) {
    // 버스트 10 을 다 쓴 뒤로는 초당 1 보다 조금 느리게
    // [JA] バースト 10 を使い切った後は毎秒 1 より少し遅く
    if (i >= 9) {
      sleep(1.1);
    }
    let s = login(demoEmail(offset + i), password);
    for (let tries = 0; s.error && s.error.startsWith('429') && tries < 5; tries++) {
      sleep(2);
      s = login(demoEmail(offset + i), password);
    }
    if (s.error) {
      fail(`로그인 실패 ${demoEmail(offset + i)}: ${s.error} — 계정이 있는지(ops/48 status) · 비밀번호 파일`);
    }
    sessions.push(s);
  }
  return sessions;
}

/** 토큰 갱신 — REFRESH 쿠키가 회전한다(같은 값을 두 번 쓰면 그 로그인이 끝난다). 한 계정은 한 VU 만 갱신한다. */
// [JA] トークン更新 — REFRESH クッキーがローテーションする(同じ値を二度使うとそのログインが終わる)。1 アカウントは 1 VU だけが更新する。
export function refresh(session) {
  const jar = new http.CookieJar();
  jar.set(GATEWAY, 'REFRESH', session.refresh, { secure: true, http_only: true, path: '/' });
  const res = http.get(`${GATEWAY}/user-service/api/token/refresh`, { headers: baseHeaders(), jar, tags: { name: 'token_refresh' } });
  ok(res, 'token_refresh');
  const access = res.headers['Access'] || res.headers['ACCESS'] || res.headers['access'];
  if (res.status !== 200 || !access) {
    return false;
  }
  session.access = access;
  session.refresh = cookieValue(res, 'REFRESH') || session.refresh;
  session.at = Date.now();
  return true;
}

/**
 * 도착률 단계 — STAGES="1m:50,3m:50,1m:0" 를 k6 stages 로. 없으면 기본값.
 * 목표는 초당 반복 수(arrival-rate) — VU 수가 아니라 손님이 오는 속도를 정한다(닫힌 모델은 서버가 느려지면 부하도 줄어 병목을 숨긴다).
 *
 * <p>[JA] 到着率の段階 — STAGES="1m:50,3m:50,1m:0" を k6 の stages に。なければ既定値。
 * 目標は毎秒の反復数(arrival-rate) — VU 数ではなく客が来る速さを決める(閉じたモデルはサーバーが遅くなると負荷も減りボトルネックを隠す)。
 */
export function stages(defaults) {
  const raw = __ENV.STAGES;
  if (!raw) {
    return defaults;
  }
  return raw.split(',').map((s) => {
    const [duration, target] = s.trim().split(':');
    if (!duration || target === undefined || Number.isNaN(Number(target))) {
      fail(`STAGES 형식: 길이:목표,… (예 1m:50,3m:50,1m:0) — ${s}`);
    }
    return { duration, target: Number(target) };
  });
}

export function rate(def) {
  const r = Number(__ENV.RATE || def);
  if (!(r > 0)) {
    fail(`RATE 는 양수 — ${__ENV.RATE}`);
  }
  return r;
}

/** 멱등 키 — 화면은 시도마다 하나(8~64자 [A-Za-z0-9_-], OrderService). */
// [JA] 冪等キー — 画面は試行ごとに 1 つ(8〜64 文字 [A-Za-z0-9_-]、OrderService)。
export function idempotencyKey() {
  let s = '';
  for (let i = 0; i < 32; i++) {
    s += Math.floor(Math.random() * 16).toString(16);
  }
  return `k6-${s}`;
}

/**
 * 담을 수 있는 상품 — 홈 섹션에서 재고가 있는 사이즈를 가진 것. SKU(색상)가 하나인 상품을 고른다:
 * catalog 의 담기(skip(style) — 순서 보장 없는 Set)와 cart 의 확인(skus.get(style))이 같은 SKU 를 가리키는지 확실한 것은 하나일 때뿐이다.
 *
 * <p>[JA] カートに入れられる商品 — ホームのセクションで在庫のあるサイズを持つもの。SKU(色)が 1 つの商品を選ぶ:
 * catalog のカート投入(skip(style) — 順序保証のない Set)と cart の確認(skus.get(style))が同じ SKU を指すと確実なのは 1 つのときだけだ。
 */
export function purchasable(home) {
  const out = [];
  for (const section of (home && home.sections) || []) {
    for (const p of section.products || []) {
      const skus = p.skus || [];
      if (skus.length !== 1) {
        continue;
      }
      const sizes = skus[0].sizes || [];
      for (let i = 0; i < sizes.length; i++) {
        if ((sizes[i].quantity || 0) - (sizes[i].onHold || 0) >= 5) {
          out.push({ id: p.id, slug: p.slug, style: 0, size: i });
          break;
        }
      }
    }
  }
  return out;
}

/** 탐색 화면의 카테고리 id — 홈 섹션의 categoryId(문자열 TSID). */
// [JA] 探索画面のカテゴリ id — ホームセクションの categoryId(文字列の TSID)。
export function categories(home) {
  const ids = new Set();
  for (const section of (home && home.sections) || []) {
    if (section.categoryId) {
      ids.add(String(section.categoryId));
    }
  }
  return Array.from(ids);
}

export function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

/** 데모 배송지 — 주문 본문의 OrderAddressDto(필수 넷 + 수취인). 화면은 저장된 기본 배송지를 그대로 싣는다. */
// [JA] デモの配送先 — 注文本文の OrderAddressDto(必須 4 項目 + 受取人)。画面は保存済みの既定の配送先をそのまま載せる。
export const FALLBACK_ADDRESS = {
  firstname: '부하',
  lastname: '시험',
  address1: '테스트로 1',
  address2: '101동 101호',
  city: '서울',
  state: '서울특별시',
  zipCode: '04524',
  country: 'KR',
  phoneNumber: '010-0000-0000',
};
