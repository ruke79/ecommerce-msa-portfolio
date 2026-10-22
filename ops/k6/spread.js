// 검색 주소 만들기 — browse 의 데이터 규모 모드가 쓴다(docs/83 §2.1). k6 모듈을 쓰지 않는 순수 함수라 LoadK6ScriptTest 가 node 로 돌려 본다.
// [JA] 検索アドレスの組み立て — browse のデータ規模モードが使う。k6 モジュールを使わない純粋関数で、LoadK6ScriptTest が node で動かす。
//
// 🐛 2026-10-19 100만 상품 회차(docs/83 §5): 검색 조합이 카테고리 × 쪽 50 · 이름 검색어 16개뿐이라 첫 1분 반 뒤로는 모두
//    search_cache(60분)에서 나왔다 — 100만에서의 개수 세기 · LIKE 비용은 처음 몇 분에만 보였다. SPREAD=1 은 조합을 넓혀
//    요청 대부분이 캐시에 없는 조합이 되게 한다(깊은 쪽 · 이름 검색의 쪽 · 가격대 필터).
// [JA] 以前は検索の組み合わせが少なく、最初の 1 分半の後はキャッシュから返った。SPREAD=1 は組み合わせを広げ、ほとんどをキャッシュ外にする。

const SEARCH = '/catalog-service/api/search';

/** lo ~ hi(둘 다 포함) 정수. */
export function between(lo, hi, rnd = Math.random) {
  return lo + Math.floor(rnd() * (hi - lo + 1));
}

/**
 * 카테고리 목록의 깊은 쪽 — 2 ~ deepPages. 주소는 예전 회차와 같다(includeFilters 를 싣지 않아 서버 기본 true — 화면은 2쪽부터
 * false 다). 바꾸면 지난 기록(docs/83 §5)과 견줄 수 없어 그대로 둔다.
 */
export function deepSearchPath(category, deepPages, rnd = Math.random) {
  const page = between(2, Math.max(2, deepPages), rnd);
  return `${SEARCH}?category=${encodeURIComponent(category)}&page=${page}&pageSize=10`;
}

/** 이름 검색 — 1 ~ namePages 쪽(1 이면 늘 첫 쪽). 첫 쪽만 필터 메뉴를 함께 받는다. */
export function nameSearchPath(term, namePages, rnd = Math.random) {
  const page = between(1, Math.max(1, namePages), rnd);
  return `${SEARCH}?search=${encodeURIComponent(term)}&page=${page}&pageSize=10&includeFilters=${page === 1}`;
}

/**
 * 필터를 건 검색(화면의 가격대 · 색상 · 사이즈) — 첫 쪽. 가격대는 하한 0 ~ 200 · 폭 20 ~ 500 이라 같은 조합이 거의 나오지 않는다.
 * 색상 · 사이즈는 그 카테고리 첫 쪽 응답의 필터 메뉴(colors · sizes)에서 고른다 — 없는 값으로 결과 0건을 재지 않게.
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
