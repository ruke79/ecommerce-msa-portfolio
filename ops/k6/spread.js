// 검색 주소 만들기 — browse 의 데이터 규모 모드가 쓴다(docs/83 §2.1). k6 모듈을 쓰지 않는 순수 함수라 LoadK6ScriptTest 가 node 로 돌려 본다.
// [JA] 検索アドレスの組み立て — browse のデータ規模モードが使う。k6 モジュールを使わない純粋関数で、LoadK6ScriptTest が node で動かす。
//
// 🐛 2026-10-19 100만 상품 회차(docs/83 §5): 검색 조합이 카테고리 × 쪽 50 · 이름 검색어 16개뿐이라 첫 1분 반 뒤로는 모두
//    search_cache(60분)에서 나왔다 — 100만에서의 개수 세기 · LIKE 비용은 처음 몇 분에만 보였다. SPREAD=1 은 조합을 넓혀
//    요청 대부분이 캐시에 없는 조합이 되게 한다(깊은 쪽 · 이름 검색의 쪽 · 가격대 필터).
// [JA] 以前は検索の組み合わせが少なく、最初の 1 分半の後はキャッシュから返った。SPREAD=1 は組み合わせを広げ、ほとんどをキャッシュ外にする。

const SEARCH = '/catalog-service/api/search';

/** lo ~ hi(둘 다 포함) 정수. */
// [JA] lo 〜 hi(両端を含む)の整数。
export function between(lo, hi, rnd = Math.random) {
  return lo + Math.floor(rnd() * (hi - lo + 1));
}

/**
 * 카테고리 목록의 깊은 쪽 — 2 ~ deepPages. 주소는 예전 회차와 같다(includeFilters 를 싣지 않아 서버 기본 true — 화면은 2쪽부터
 * false 다). 바꾸면 지난 기록(docs/83 §5)과 견줄 수 없어 그대로 둔다.
 *
 * <p>[JA] カテゴリ一覧の深い側 — 2 〜 deepPages。アドレスは以前の回と同じ(includeFilters を載せないのでサーバー既定の true — 画面は 2 ページ目から
 * false だ)。変えると過去の記録(docs/83 §5)と比べられないのでそのままにする。
 */
export function deepSearchPath(category, deepPages, rnd = Math.random) {
  const page = between(2, Math.max(2, deepPages), rnd);
  // 화면(browse.jsx)처럼 2쪽부터는 메뉴를 받지 않는다 — 🐛 2026-10-21 전에는 빠져 서버 기본값(true)으로 깊은 쪽도 메뉴를 붙여 기다렸다
  // [JA] 画面(browse.jsx)のように 2 ページ目からはメニューを受け取らない — 🐛 2026-10-21 以前は抜けていてサーバー既定値(true)で深いページもメニューを付けて待っていた
  return `${SEARCH}?category=${encodeURIComponent(category)}&page=${page}&pageSize=10&includeFilters=false`;
}

/** 이름 검색 — 1 ~ namePages 쪽(1 이면 늘 첫 쪽). 첫 쪽만 필터 메뉴를 함께 받는다. */
// [JA] 名前検索 — 1 〜 namePages ページ(1 なら常に最初のページ)。最初のページだけフィルターメニューを一緒に受け取る。
export function nameSearchPath(term, namePages, rnd = Math.random) {
  const page = between(1, Math.max(1, namePages), rnd);
  return `${SEARCH}?search=${encodeURIComponent(term)}&page=${page}&pageSize=10&includeFilters=${page === 1}`;
}

/**
 * 필터를 건 검색(화면의 가격대 · 색상 · 사이즈) — 첫 쪽. 가격대는 하한 0 ~ 200 · 폭 20 ~ 500 이라 같은 조합이 거의 나오지 않는다.
 * 색상 · 사이즈는 그 카테고리 첫 쪽 응답의 필터 메뉴(colors · sizes)에서 고른다 — 없는 값으로 결과 0건을 재지 않게.
 *
 * <p>[JA] フィルターをかけた検索(画面の価格帯 · 色 · サイズ) — 最初のページ。価格帯は下限 0 〜 200 · 幅 20 〜 500 なので同じ組み合わせはほとんど出ない。
 * 色 · サイズはそのカテゴリの最初のページの応答のフィルターメニュー(colors · sizes)から選ぶ — ない値で結果 0 件を測らないように。
 */
export function filterSearchPath(category, facets, rnd = Math.random) {
  const low = between(0, 200, rnd);
  const high = low + between(20, 500, rnd);
  let q = `category=${encodeURIComponent(category)}&lowPrice=${low}&highPrice=${high}`;
  const colors = usable(facets && facets.colors);
  if (colors.length && rnd() < 0.5) {
    q += `&color=${encodeURIComponent(colors[Math.floor(rnd() * colors.length)])}`;
  }
  const sizes = usable(facets && facets.sizes);
  if (sizes.length && rnd() < 0.3) {
    q += `&size=${encodeURIComponent(sizes[Math.floor(rnd() * sizes.length)])}`;
  }
  return `${SEARCH}?${q}&page=1&pageSize=10&includeFilters=true`;
}

function usable(values) {
  return (Array.isArray(values) ? values : []).filter((v) => typeof v === 'string' && v.trim() !== '');
}
