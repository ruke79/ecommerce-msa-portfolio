// 급증 시나리오 — 조회를 짧게 몰아 HPA(k8s/70, 최소 2 · 최대 3)가 늘어나는 동안 오류가 나는지 본다. 실행: ./ops/53-load-k6.sh spike (docs/83 §2).
// [JA] 急増シナリオ — 閲覧を短く集中させ、HPA が増える間にエラーが出るかを見る。
//
// 볼 것: 새 파드가 Ready 가 되기 전(readiness)에 트래픽을 받지 않는가, 커넥션 풀 총합이 DB 한도를 넘지 않는가(docs/19 §3 —
// 예전에는 스케일아웃하는 순간 파드가 DB 연결을 못 얻어 기동에 실패했다). 같은 시간에 `kubectl -n ecommerce get hpa -w`.
import { browse, setup as browseSetup } from './browse.js';
import { rate, stages } from './lib.js';

const PEAK = rate(200);
const BASE = Math.max(1, Math.round(PEAK / 10));

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: BASE,
      timeUnit: '1s',
      preAllocatedVUs: Math.max(20, PEAK),
      maxVUs: Math.max(100, PEAK * 4),
      stages: stages([
        { duration: '2m', target: BASE },     // 평소
        { duration: '20s', target: PEAK },    // 몰림
        { duration: '3m', target: PEAK },     // HPA 가 따라오는 동안
        { duration: '20s', target: BASE },    // 빠짐
        { duration: '3m', target: BASE },     // 줄어드는 동안(scale-in 안정화 창)
      ]),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.02'],
    'http_req_duration{name:product}': ['p(99)<3000'],
    checks: ['rate>0.98'],
    not_json: ['count<1'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export const setup = browseSetup;
export default browse;
