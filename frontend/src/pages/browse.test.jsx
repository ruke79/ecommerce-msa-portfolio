import { render, act } from "@testing-library/react";
import {
  applyFilter,
  subCategoryChanges,
  buildSearchRequest,
  nextPageParam,
  sortProducts,
  toggleInMultiValue,
} from "pages/browse";
import { useInfiniteScroll } from "hook/hooks";

const page = (count, total) => ({
  product: { content: Array.from({ length: count }, (_, i) => ({ id: `p${i}` })) },
  totalProducts: total,
});

describe("nextPageParam — 어디까지 더 불러올지", () => {
  it("아직 남았으면 다음 번호를 준다", () => {
    expect(nextPageParam(page(10, 25), [page(10, 25)])).toBe(2);
    expect(nextPageParam(page(10, 25), [page(10, 25), page(10, 25)])).toBe(3);
  });

  it("다 불러왔으면 멈춘다", () => {
    const pages = [page(10, 20), page(10, 20)];
    expect(nextPageParam(page(10, 20), pages)).toBeUndefined();
  });

  /**
   * 🐛 무한 루프 방지. totalProducts 는 상품 수를 따로 센 값이라 실제로 돌아오는 목록과
   * 어긋날 수 있다. 그때 "아직 남았다"로 판단하면 감시 대상이 화면에 계속 남아 있으므로
   * 빈 응답을 부르고 → 또 부르고 → 브라우저가 멈출 때까지 요청이 나간다.
   */
  it("빈 페이지가 오면 총계가 더 크다고 해도 멈춘다", () => {
    const pages = [page(10, 999), page(0, 999)];
    expect(nextPageParam(page(0, 999), pages)).toBeUndefined();
  });

  it("응답이 망가져 있어도 터지지 않고 멈춘다", () => {
    expect(nextPageParam(null, [null])).toBeUndefined();
    expect(nextPageParam({}, [{}])).toBeUndefined();
  });
});

describe("buildSearchRequest — URL 조건을 검색 파라미터로", () => {
  const params = (qs) => new URLSearchParams(qs);

  /**
   * 🐛 회귀 대상: **별점 필터가 검색에 반영된 적이 없다.**
   *
   * URL 에 들어가는 이름은 `rating` 인데(ratingHandler → toggleParam('rating', …),
   * HeadingFilter 도 `searchParams.get('rating')` 으로 체크 상태를 그린다) 요청을 만들 때는
   * `searchParams.get("rate")` 를 읽었다. 체크박스는 켜지는데 결과는 그대로였다.
   */
  it("별점은 'rating' 에서 읽는다 — 'rate' 가 아니다", () => {
    expect(buildSearchRequest(params("rating=4"), 1).rating).toBe(4);

    // 예전 이름으로는 아무 일도 일어나지 않아야 한다(그래야 진짜로 고쳐진 것이다).
    expect(buildSearchRequest(params("rate=4"), 1).rating).toBeUndefined();
  });

  /**
   * 🔴 회귀 대상 2: 이름을 고친 뒤에도 <b>한 겹이 더 남아 있었다.</b>
   *
   * 이 테스트는 원래 `toEqual({ rating: 4 })` 였다 — 즉 <b>틀린 모양을 고정하고
   * 있었다.</b> axios 는 객체를 `rating[rating]=4` 로 직렬화하므로 서버에는 `rating`
   * 이라는 파라미터가 도착하지 않고, 스프링은 float 기본값 0 으로 바인딩한다.
   * `params.getRating() != 0.0f` 가 거짓이라 조건이 붙지 않는다.
   *
   * 바로 옆 `shipping` 은 `shipping.shipping` 으로 풀어 보내고 있었다 — 별점만 빠졌다.
   */
  it("🔴 별점은 숫자로 보낸다 — 객체면 서버에 도착하지 않는다", () => {
    const req = buildSearchRequest(params("rating=4"), 1);

    expect(typeof req.rating).toBe("number");
    expect(req.rating).not.toEqual({ rating: 4 });
  });

  it("페이지 번호는 URL 이 아니라 인자에서 온다", () => {
    // 예전 페이지네이션 링크(?page=7)를 북마크해 뒀더라도 무한 스크롤은 1페이지부터다.
    expect(buildSearchRequest(params("page=7"), 1).page).toBe(1);
    expect(buildSearchRequest(params("page=7"), 3).page).toBe(3);
  });

  it("페이지 번호가 이상하면 1로 맞춘다", () => {
    expect(buildSearchRequest(params(""), 0).page).toBe(1);
    expect(buildSearchRequest(params(""), -5).page).toBe(1);
    expect(buildSearchRequest(params(""), undefined).page).toBe(1);
  });

  it("여러 개를 고른 필터는 정규식으로 합쳐 보낸다", () => {
    expect(buildSearchRequest(params("brand=nike_adidas"), 1).brand)
      .toBe("^nike|^adidas");
  });

  /**
   * 🐛 서버는 매 요청마다 필터 메뉴(카테고리 전체·하위 카테고리 전체·색상·사이즈·
   * 상세·브랜드)를 함께 실어 보냈다. 그런데 화면은 **첫 페이지 것만 쓰고 나머지를
   * 버린다**(pages[0] 에서만 읽는다). 무한 스크롤을 붙인 뒤로는 스크롤할 때마다
   * 쓰지도 않을 카테고리 전체가 따라왔다.
   */
  it("필터 메뉴는 첫 페이지에서만 요청한다", () => {
    expect(buildSearchRequest(params(""), 1).includeFilters).toBe(true);
    expect(buildSearchRequest(params(""), 2).includeFilters).toBe(false);
    expect(buildSearchRequest(params(""), 9).includeFilters).toBe(false);
  });

  it("페이지 번호가 이상해도 1로 보정된 뒤 판단한다", () => {
    // page=0 은 1로 보정된다 — 그러면 첫 페이지이므로 필터가 필요하다.
    expect(buildSearchRequest(params(""), 0).includeFilters).toBe(true);
    expect(buildSearchRequest(params(""), undefined).includeFilters).toBe(true);
  });

  it("고르지 않은 필터는 빈 값으로 둔다 — 서버 계약 그대로다", () => {
    const req = buildSearchRequest(params(""), 1);
    expect(req.search).toEqual({});
    expect(req.brand).toEqual({});
    expect(req.style).toBeNull();
    expect(req.pageSize).toBe(10);
  });

  it("가격 범위를 lowPrice/highPrice 로 나눈다", () => {
    const req = buildSearchRequest(params("price=10_90"), 1);
    expect(req.lowPrice).toBe(10);
    expect(req.highPrice).toBe(90);
  });

  /**
   * 🔴 회귀 대상: <b>한쪽만 있는 가격 범위가 카탈로그 전체를 돌려줬다.</b>
   *
   * 예전 코드는 `Number(priceQuery[1]) || 0` 이라 비어 있는 상한이 **0** 이 됐다.
   * 화면의 "500 이상"(price=500_)은 `500 ~ 0` 이라는 불가능한 범위로 나갔고, 서버는
   * 아무것도 못 찾은 뒤 그 "없음"을 다시 전체 상품으로 뒤집었다(SearchService 1번 조건).
   *
   * 비었으면 **보내지 않는다** — axios 가 undefined 를 빼고, 서버는 null 을 "그 경계
   * 없음" 으로 읽는다.
   */
  it("🔴 비어 있는 경계는 0 이 아니라 '없음' 이다", () => {
    const overFiveHundred = buildSearchRequest(params("price=500_"), 1);
    expect(overFiveHundred.lowPrice).toBe(500);
    expect(overFiveHundred.highPrice).toBeUndefined();

    const underFifty = buildSearchRequest(params("price=_50"), 1);
    expect(underFifty.lowPrice).toBeUndefined();
    expect(underFifty.highPrice).toBe(50);
  });

  it("0 은 진짜 0 이다 — 비어 있는 것과 구분한다", () => {
    const req = buildSearchRequest(params("price=0_10"), 1);
    expect(req.lowPrice).toBe(0);
    expect(req.highPrice).toBe(10);
  });

  it("가격을 고르지 않으면 두 경계 모두 보내지 않는다", () => {
    const req = buildSearchRequest(params(""), 1);
    expect(req.lowPrice).toBeUndefined();
    expect(req.highPrice).toBeUndefined();
  });
});

/**
 * 여러 개를 고르는 필터의 켜고 끄기.
 *
 * <p>🐛 예전 구현은 `String.prototype.search(value)` 를 썼다 — 그 함수의 인자는
 * <b>정규식</b>이다. 문자열을 주면 브라우저가 정규식으로 컴파일한다.
 */
describe("toggleInMultiValue — `_` 로 이어 붙인 다중 선택", () => {
  it("없으면 켠다", () => {
    expect(toggleInMultiValue(null, "Red")).toEqual({ result: "Red", active: false });
    expect(toggleInMultiValue("Red", "Blue")).toEqual({ result: "Red_Blue", active: false });
  });

  it("켜져 있으면 끈다", () => {
    expect(toggleInMultiValue("Red_Blue", "Red")).toEqual({ result: "Blue", active: true });
    expect(toggleInMultiValue("Red_Blue_Green", "Blue"))
      .toEqual({ result: "Red_Green", active: true });
  });

  /** 마지막 하나를 끄면 필터 자체가 사라진다 — `?color=` 를 남기면 서버가 빈 값으로 검색한다. */
  it("마지막 하나를 끄면 빈 객체 — 필터를 지우라는 뜻이다", () => {
    expect(toggleInMultiValue("Red", "Red")).toEqual({ result: {}, active: true });
  });

  /**
   * 🔴 회귀 대상: 정규식 메타문자가 든 값.
   *
   * 브랜드/스타일 이름이 `"C++"` 이면 `"Red".search("C++")` 가
   * <b>SyntaxError: Nothing to repeat</b> 를 던졌다 — 필터를 그리는 중이라
   * 화면 전체가 죽는다(실측). `.` 은 더 고약하다: 터지지 않고 <b>조용히 틀린다.</b>
   */
  it("🔴 정규식 메타문자가 든 값에도 터지지 않는다", () => {
    for (const value of ["C++", "*Star", "?", "(Pack)"]) {
      expect(() => toggleInMultiValue("Red", value)).not.toThrow();
      expect(toggleInMultiValue("Red", value).result).toBe(`Red_${value}`);
    }

    // `C.C` 를 정규식으로 읽으면 `.` 이 아무 글자나 맞아 `CXC` 도 선택됨이 된다.
    expect(toggleInMultiValue("CXC", "C.C").active).toBe(false);
    // `(Pack)` 의 괄호는 그룹이라 정규식으로는 `Pack` 과 같아진다.
    expect(toggleInMultiValue("Pack", "(Pack)").active).toBe(false);
  });

  /** 🔴 부분 일치를 "선택됨" 으로 읽으면 안 된다 — `Green` 이 켜졌다고 `Gre` 가 켜진 건 아니다. */
  it("🔴 부분 일치는 선택이 아니다", () => {
    expect(toggleInMultiValue("Green", "Gre").active).toBe(false);
    expect(toggleInMultiValue("Green", "Gre").result).toBe("Green_Gre");
  });
});

describe("sortProducts — 이어 붙인 전체를 정렬한다", () => {
  const product = (id, rating, prices) => ({
    id,
    rating,
    createdAt: `2024-01-0${id}`,
    skus: [{ sold: 1, sizes: prices.map((price) => ({ price })) }],
  });

  it("가격 낮은 순", () => {
    const list = [product("1", 1, [50]), product("2", 1, [10]), product("3", 1, [30])];
    expect(sortProducts(list, "priceLowToHight").map((p) => p.id))
      .toEqual(["2", "3", "1"]);
  });

  it("가격 높은 순", () => {
    const list = [product("1", 1, [50]), product("2", 1, [10]), product("3", 1, [30])];
    expect(sortProducts(list, "priceHighToLow").map((p) => p.id))
      .toEqual(["1", "3", "2"]);
  });

  /** 🐛 예전에는 정가로 정렬했다 — 카드는 할인가를 보여 주므로 30% 할인된 $70 짜리가 $80 짜리보다 뒤에 섰다. */
  it("화면에 보이는 가격(할인 반영)으로 정렬한다", () => {
    const discountedOne = { ...product("1", 1, [100]), skus: [{ sold: 1, discount: 30, sizes: [{ price: 100 }] }] };
    const list = [product("2", 1, [80]), discountedOne];
    expect(sortProducts(list, "priceLowToHight").map((p) => p.id)).toEqual(["1", "2"]);
  });

  /**
   * 🐛 회귀 대상: 예전에는 loader 가 **그 페이지 10개만** 정렬했다. 페이지네이션일 때는
   * 한 화면에 한 페이지만 보여 그럴듯했지만, 무한 스크롤에서는 페이지 경계마다 가격이
   * 처음으로 튀어 오르는 게 그대로 보인다. 이어 붙인 뒤 한 번에 정렬해야 한다.
   */
  it("두 페이지를 이어 붙여도 경계에서 순서가 튀지 않는다", () => {
    const first = [product("1", 1, [10]), product("2", 1, [90])];
    const second = [product("3", 1, [20]), product("4", 1, [80])];

    const sorted = sortProducts([...first, ...second], "priceLowToHight");
    const prices = sorted.map((p) => p.skus[0].sizes[0].price);

    expect(prices).toEqual([...prices].sort((a, b) => a - b));
  });

  /**
   * 목록은 React Query 캐시 안의 배열이다. 제자리에서 정렬하면 같은 데이터를 보는 다른
   * 렌더와 어긋나고, 페이지를 더 불러올 때마다 순서가 누적으로 뒤섞인다.
   */
  it("입력을 건드리지 않는다", () => {
    const list = [product("1", 1, [50]), product("2", 1, [10])];
    const before = JSON.stringify(list);

    sortProducts(list, "priceLowToHight");
    sortProducts(list, "popular");
    sortProducts(list, "topSelling");

    expect(JSON.stringify(list)).toBe(before);
  });

  it("sku 나 sizes 가 비어 있어도 터지지 않는다", () => {
    // 예전 코드는 `sku.sizes.sort(...)` 뒤에 `sizes[0]["price"]` 를 읽어 여기서 죽었다.
    const broken = [{ id: "1", skus: [] }, { id: "2", skus: [{ sizes: [] }] }];

    expect(() => sortProducts(broken, "priceLowToHight")).not.toThrow();
    expect(sortProducts(broken, "priceHighToLow")).toHaveLength(2);
  });

  it("모르는 정렬값은 원래 순서 그대로", () => {
    const list = [product("1", 1, [50]), product("2", 1, [10])];
    expect(sortProducts(list, "").map((p) => p.id)).toEqual(["1", "2"]);
  });
});

/**
 * 🐛 회귀 대상: **무한 스크롤이 연결된 적이 없다.**
 *
 * `useInfiniteScroll` 은 정의만 있고 부르는 곳이 한 군데도 없었다(browse.jsx 는 MUI
 * Pagination 을 썼다). 그 전에는 훅 자체도 죽어 있었다 — 첫 줄의
 * `useRef < HTMLDivElement > (null)` 은 TS 제네릭이 아니라 비교 연산 두 번으로 읽혀
 * ref 에 불리언이 들어갔다.
 */
describe("useInfiniteScroll — 감시 대상이 보이면 다음 페이지", () => {
  let observed;
  let callbacks;
  let disconnects;

  beforeEach(() => {
    observed = [];
    callbacks = [];
    disconnects = 0;
    global.IntersectionObserver = class {
      constructor(cb) {
        callbacks.push(cb);
      }
      observe(el) {
        observed.push(el);
      }
      disconnect() {
        disconnects += 1;
      }
    };
  });

  // 훅만 떼어 돌리면 ref 가 아무 DOM 에도 붙지 않아 효과가 곧장 return 한다.
  // 실제 컴포넌트처럼 엘리먼트에 붙여야 observer 가 만들어진다.
  const Probe = ({ hasNextPage, fetchNextPage }) => {
    const ref = useInfiniteScroll({ hasNextPage, fetchNextPage });
    return <div ref={ref} data-testid="sentinel" />;
  };

  const intersect = (isIntersecting) =>
    act(() => callbacks[callbacks.length - 1]([{ isIntersecting }]));

  it("ref 가 실제 엘리먼트에 붙고 observer 가 그것을 감시한다", () => {
    // 예전에는 ref 자리에 불리언 false 가 들어가 observer 자체가 만들어지지 않았다.
    const { getByTestId } = render(
      <Probe hasNextPage={true} fetchNextPage={jest.fn()} />
    );

    expect(observed).toHaveLength(1);
    expect(observed[0]).toBe(getByTestId("sentinel"));
  });

  it("화면에 들어오면 다음 페이지를 부른다", () => {
    const fetchNextPage = jest.fn();
    render(<Probe hasNextPage={true} fetchNextPage={fetchNextPage} />);

    intersect(true);

    expect(fetchNextPage).toHaveBeenCalledTimes(1);
  });

  it("더 없으면 부르지 않는다", () => {
    const fetchNextPage = jest.fn();
    render(<Probe hasNextPage={false} fetchNextPage={fetchNextPage} />);

    intersect(true);

    expect(fetchNextPage).not.toHaveBeenCalled();
  });

  it("보이지 않으면 부르지 않는다", () => {
    const fetchNextPage = jest.fn();
    render(<Probe hasNextPage={true} fetchNextPage={fetchNextPage} />);

    intersect(false);

    expect(fetchNextPage).not.toHaveBeenCalled();
  });

  it("언마운트하면 observer 를 끊는다 — 화면을 떠난 뒤 요청이 나가면 안 된다", () => {
    const { unmount } = render(
      <Probe hasNextPage={true} fetchNextPage={jest.fn()} />
    );

    unmount();

    expect(disconnects).toBeGreaterThan(0);
  });
});

/**
 * 🐛 회귀 대상: **카테고리를 고르면 다른 필터가 전부 사라졌고, 다른 필터를 고르면 카테고리가
 * 사라졌다.** 핸들러는 자기 필터 하나만 넘기는데(`filter({ category })`), 토글이 언급하지
 * 않은 필터(`undefined`)를 "비었다" 로 읽어 지웠다. `/browse?category=3` 에서 색상 하나를
 * 고르는 순간 전체 카테고리로 돌아갔다 — "카테고리 필터가 안 먹는다" 의 정체.
 */
describe("applyFilter — 언급한 필터만 바꾼다", () => {
  const params = (init) => new URLSearchParams(init);

  it("🔴 카테고리를 골라도 검색어·색상·브랜드는 그대로다", () => {
    const p = params({ search: "shirt", color: "red", brand: "nike" });
    applyFilter(p, { category: "3" });
    expect(p.get("category")).toBe("3");
    expect(p.get("search")).toBe("shirt");
    expect(p.get("color")).toBe("red");
    expect(p.get("brand")).toBe("nike");
  });

  it("🔴 카테고리가 걸린 채 색상을 골라도 카테고리는 남는다", () => {
    const p = params({ category: "3" });
    applyFilter(p, { color: "red" });
    expect(p.get("category")).toBe("3");
    expect(p.get("color")).toBe("red");
  });

  it("같은 값을 다시 누르면 꺼진다", () => {
    const p = params({ category: "3", search: "shirt" });
    applyFilter(p, { category: "3" });
    expect(p.get("category")).toBeNull();
    expect(p.get("search")).toBe("shirt");
  });

  it("숫자 id 도 문자열과 같은 값으로 본다 — c.id 가 숫자로 와도 토글된다", () => {
    const p = params({ category: "3" });
    applyFilter(p, { category: 3 });
    expect(p.get("category")).toBeNull();
  });

  it("빈 값({} · '' · null)은 '없애 달라' 다", () => {
    const p = params({ search: "shirt", sort: "priceLowToHight", size: "M", category: "3" });
    applyFilter(p, { search: {} });
    applyFilter(p, { sort: "" });
    applyFilter(p, { size: null });
    expect(p.get("search")).toBeNull();
    expect(p.get("sort")).toBeNull();
    expect(p.get("size")).toBeNull();
    expect(p.get("category")).toBe("3");   // 언급하지 않았다
  });

  it("상위 카테고리가 다른 것으로 바뀌면 하위는 지운다 — 이전 상위의 것이라 AND 로 묶이면 아무것도 안 나온다", () => {
    const p = params({ category: "3", subCategory: "31" });
    applyFilter(p, { category: "4" });
    expect(p.get("category")).toBe("4");
    expect(p.get("subCategory")).toBeNull();
  });

  it("상위를 끄기만 하면 하위는 그대로다 — 하위만으로도 유효한 조건이다", () => {
    const p = params({ category: "3", subCategory: "31" });
    applyFilter(p, { category: "3" });
    expect(p.get("category")).toBeNull();
    expect(p.get("subCategory")).toBe("31");
  });

  /** 🐛 2026-10-26 운영: 다른 상위의 하위를 누르면 하위만 바뀌어 AND 로 0건 → 서버가 카테고리를 끝까지 훑어 503. */
  it("다른 상위의 하위를 누르면 상위도 그 하위의 상위로 바꾼다", () => {
    const p = params({ category: "2" });                       // consumer electronics
    applyFilter(p, subCategoryChanges(p, "55", "4"));           // food › dairy products
    expect(p.get("category")).toBe("4");
    expect(p.get("subCategory")).toBe("55");
  });

  it("상위가 없을 때 하위를 누르면 상위도 함께 고른다", () => {
    const p = params({});
    applyFilter(p, subCategoryChanges(p, "55", 4));
    expect(p.get("category")).toBe("4");
    expect(p.get("subCategory")).toBe("55");
  });

  it("같은 상위의 하위는 하위만 바꾸고, 다시 누르면 하위만 꺼진다", () => {
    const p = params({ category: "4" });
    applyFilter(p, subCategoryChanges(p, "55", "4"));
    expect(p.toString()).toBe("category=4&subCategory=55");
    applyFilter(p, subCategoryChanges(p, "55", "4"));
    expect(p.toString()).toBe("category=4");
  });

  it("page 는 언제나 지운다 — 무한 스크롤에서는 URL 에 두지 않는다", () => {
    const p = params({ page: "3", category: "3" });
    applyFilter(p, { color: "red" });
    expect(p.get("page")).toBeNull();
  });

  it("아무것도 넘기지 않으면 page 말고는 손대지 않는다", () => {
    const p = params({ category: "3", search: "x", page: "2" });
    applyFilter(p, {});
    expect(p.toString()).toBe("category=3&search=x");
  });
});
