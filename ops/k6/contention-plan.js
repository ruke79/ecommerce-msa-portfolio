// 재고 경합의 계산 — contention.js 가 쓴다. k6 모듈을 쓰지 않는 순수 함수라 LoadK6ScriptTest 가 node 로 돌려 본다(docs/83 §2.3).
// [JA] 在庫競合の計算 — contention.js が使う。k6 モジュールを使わない純粋関数で、LoadK6ScriptTest が node で動かす。

/**
 * 회차 r 의 시각 — 모든 VU 가 start 에 함께 주문하고(한꺼번에 몰리게), release 에 받아진 주문을 함께 취소한다.
 * 회차 사이 gap 초는 취소 · 줄 빼기가 끝나고 재고가 돌아오는 시간이다.
 */
export function roundWindow(t0, round, holdSec, gapSec) {
  const start = t0 + round * (holdSec + gapSec) * 1000;
  return { start, release: start + holdSec * 1000 };
}

/**
 * 주문 생성 · 입금 안내 응답의 분류.
 *   ok      — 받아졌다
 *   short   — 재고가 모자라 거절(400 "재고가 부족해 …") — 경합에서 기대한 결과다, 실패가 아니다
 *   refused — 그 밖의 400(요청 · 계정 상태 — 입금 대기 한도 등) — 시나리오가 잘못 짜인 것
 *   error   — 5xx · 응답 없음 · 그 밖
 */
export function classify(status, body) {
  if (status === 200) {
    return 'ok';
  }
  if (status === 400) {
    // 본문의 message 를 본다 — JSON 이 한글을 \uXXXX 로 적어 보내도(가짜 서버 시험에서 그랬다) 같은 글자다
    let text = String(body || '');
    try {
      const o = JSON.parse(text);
      if (o && typeof o.message === 'string') {
        text = o.message;
      }
    } catch (e) {
      // JSON 이 아니면 본문 그대로
    }
    return /재고가 부족/.test(text) ? 'short' : 'refused';
  }
  return 'error';
}

/**
 * 회차의 판정 — 받아진 주문(입금 안내까지 간 것)이 재고를 넘으면 초과 판매다. 덜 팔린 것(재고가 남았는데 거절된 손님)도 따로 센다:
 * 잠금 대기가 길어 거절됐거나, 앞선 회차의 예약이 아직 풀리지 않았다.
 */
export function verdict(stock, accepted) {
  return { oversold: Math.max(0, accepted - stock), unsold: Math.max(0, stock - accepted) };
}
