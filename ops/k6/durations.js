// k6 단계 길이 계산 — k6 모듈을 쓰지 않는 순수 함수(LoadK6ScriptTest 가 node 로 돌린다).
// [JA] k6 のステージ長の計算 — k6 モジュールを使わない純粋関数。

/** 단계 길이의 합(초) — "1m" · "30s" · "1m30s" · "500ms". */
export function totalSeconds(list) {
  let sec = 0;
  for (const st of list) {
    const re = /(\d+)(ms|s|m|h)/g;
    let m;
    while ((m = re.exec(st.duration)) !== null) {
      sec += Number(m[1]) * { ms: 0.001, s: 1, m: 60, h: 3600 }[m[2]];
    }
  }
  return Math.max(1, Math.round(sec));
}
