// 점검 — 조회 한 번 · 주문 한 번(데모 계정 하나). 본 측정 전에 경로 · 계정 · 데이터 · 인증서가 맞는지 본다. 실행: ./ops/53-load-k6.sh smoke
// [JA] 点検 — 閲覧一回 · 注文一回。本測定の前に経路 · アカウント · データ · 証明書を確かめる。
//
// 하나라도 실패하면 종료 코드가 0 이 아니다(임계값 checks == 100%). 측정 결과로 쓰지 않는다.
import { browse } from './browse.js';
import { order, setup as orderSetup } from './order.js';

export const options = {
  setupTimeout: '120s',
  scenarios: {
    browse_once: { executor: 'per-vu-iterations', vus: 1, iterations: 1, exec: 'browseOnce' },
    order_once: { executor: 'per-vu-iterations', vus: 1, iterations: 1, exec: 'orderOnce', startTime: '2s' },
  },
  thresholds: {
    checks: ['rate==1'],
    not_json: ['count<1'],
    rate_limited: ['count<1'],
  },
};

// order 의 setup 이 홈(상품 · 카테고리)과 로그인을 함께 준비한다 — USERS 는 ops/53 이 smoke 에서 1 로 넘긴다
export const setup = orderSetup;

export function browseOnce(data) {
  browse(data);
}

export function orderOnce(data) {
  order(data);
}
