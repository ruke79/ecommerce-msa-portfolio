import { filterArray, removeDublicates } from "util/array_utils";
import Header from "pages/header/Header";
import { useTranslation } from "i18n/I18nProvider";
import { useLoaderData, useNavigate, useNavigation, useSearchParams } from "react-router-dom";
import ProductCard from "components/home/ProductCard";
import { ProductGridSkeleton } from "components/loader/Skeleton";
import { ChevronRightIcon } from "@heroicons/react/24/solid";
import CategoriesFilter from "components/browse/categoriesFilter/CategoriesFilter";
import SizesFilter from "components/browse/sizesFilter/SizesFilter";
import ColorsFilter from "components/browse/colorsFilter/ColorsFilter";
import BrandsFilter from "components/browse/brandsFilter/BrandsFilter";
import StylesFilter from "components/browse/stylesFilter/StylesFilter";
import MaterialsFilter from "components/browse/materialsFilter/MaterialsFilter";
import GenderFilter from "components/browse/genderFilter/GenderFilter";
import HeadingFilter, { splitPriceParam } from "components/browse/headingFilter/HeadingFilter";
// 페이지네이션은 무한 스크롤로 대체했다. 코드는 남겨 둔다 — 아래 렌더 부분의
// "── 페이지네이션(보존) ──" 주석 블록을 참고.
// import { Pagination } from "@mui/material";
// [JA] ページネーションは無限スクロールに置き換えた。コードは残しておく — 下の描画部分の
//      「── ページネーション(保存) ──」コメントブロックを参照。
import { useEffect, useMemo, useRef, useState } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { DotLoader } from "react-spinners";
import { getRequest } from "util/api";
import { useInfiniteScroll } from "hook/hooks";
import { timeOf } from "util/datetime";
import { skuPriceRange } from "util/pricing";

/** 한 번에 불러오는 상품 수. 예전 페이지네이션의 페이지 크기와 같다. */
// [JA] 一度に読み込む商品数。以前のページネーションのページサイズと同じ。
const PAGE_SIZE = 10;

/**
 * 다음에 부를 페이지 번호. 더 없으면 undefined (React Query 가 hasNextPage=false 로 읽는다).
 *
 * <p>빈 페이지가 오면 <b>무조건</b> 멈춘다. `totalProducts` 는 필터 조건으로 따로 센
 * 값이라 실제로 돌려주는 목록과 어긋날 수 있는데(중복 제거·권한 필터 등), 그때도
 * "아직 남았다"로 판단하면 빈 응답을 영원히 다시 부르는 무한 루프가 된다. 감시 대상이
 * 계속 화면에 남아 있으므로 브라우저가 멈출 때까지 요청이 나간다.
 *
 * <p>[JA] 次に呼ぶページ番号。もうなければ undefined(React Query が hasNextPage=false と読む)。空のページが来たら<b>無条件に</b>止める。
 * `totalProducts` はフィルタ条件で別に数えた値なので実際に返す一覧とずれることがあるが(重複除去・権限フィルタ等)、そのときも「まだ残っている」と
 * 判断すると空の応答を永遠に呼び直す無限ループになる。監視対象が画面に残り続けるので、ブラウザが固まるまでリクエストが出る。
 */
export function nextPageParam(lastPage, allPages) {
    const lastCount = lastPage?.product?.content?.length ?? 0;
    if (lastCount === 0) return undefined;

    const loaded = allPages.reduce(
        (n, p) => n + (p?.product?.content?.length ?? 0), 0);
    const total = lastPage?.totalProducts ?? 0;

    return loaded < total ? allPages.length + 1 : undefined;
}


/** URL 필터 파라미터. `page` 는 여기 없다 — 무한 스크롤에서는 URL 에 두지 않는다. */
// [JA] URL のフィルタパラメータ。`page` はここにない — 無限スクロールでは URL に置かない。
const FILTER_PARAMS = [
    "search", "category", "subCategory", "brand", "style", "size", "color",
    "material", "gender", "price", "shipping", "rating", "sort",
];

/**
 * URL 의 필터 파라미터를 바꾼다 — <b>언급된 것만</b>.
 *
 * <h3>🐛 카테고리를 고르면 다른 필터가 전부 사라졌다 (그리고 그 반대도)</h3>
 *
 * <p>핸들러는 자기 필터 하나만 넘긴다: `filter({ category })`. 나머지 열두 개는
 * `undefined` 다. 그런데 토글 함수가 `!param` 이면 <b>지우도록</b> 바뀌어 있었다 — 원래는
 * "값이 없으면 아무것도 하지 않는다" 였는데, 같은 값을 다시 눌러 끄는 기능을 붙이면서
 * "비었으면 지운다" 로 합쳐졌다. 그래서:
 *
 * <ul>
 *   <li>`?category=3` 에서 색상을 고르면 → `category` 가 지워져 <b>전체 카테고리</b>가 나온다.</li>
 *   <li>검색어를 넣고 카테고리를 고르면 → 검색어가 사라진다.</li>
 *   <li>브랜드·사이즈·가격… 무엇을 눌러도 <b>그것 하나만 남는다.</b></li>
 * </ul>
 *
 * <p>사용자에게는 "카테고리를 골랐는데 필터가 안 먹는다" 로 보였다 — 카테고리는 잠깐
 * 걸렸다가 다음 클릭에 풀리기 때문이다.
 *
 * <h3>규칙</h3>
 *
 * <ul>
 *   <li><b>`undefined`</b> — 언급하지 않았다. <b>손대지 않는다.</b></li>
 *   <li><b>빈 값</b>(`{}`·`""`·`null`) — "이 필터를 없애 달라". 지운다. (searchHandler·
 *       sortHandler 가 `{}` 를, 다중 선택의 마지막 해제가 `{}` 를 넘긴다.)</li>
 *   <li><b>같은 값</b> — 다시 눌렀다. 끈다.</li>
 *   <li><b>다른 값</b> — 켠다. 카테고리가 <b>다른 것으로 바뀌면</b> 하위 카테고리는 지운다 —
 *       그 하위는 이전 상위의 것이라 새 상위와 AND 로 묶이면 아무것도 안 나온다.</li>
 * </ul>
 *
 * <p>`page` 는 언제나 지운다. 페이지 번호는 React Query 의 pageParam 이 갖고, URL 에 남으면
 * 캐시 키만 갈라 놓는다(예전 페이지네이션의 흔적).
 *
 * @param {URLSearchParams} searchParams 제자리에서 바꾼다
 * @param {object} changes 바꿀 필터만 담은 객체
 * @returns {URLSearchParams} 같은 객체
 *
 * <p>[JA] URL のフィルタパラメータを変える — <b>言及されたものだけ</b>。🐛 カテゴリを選ぶと他のフィルタがすべて消えた(その逆も)。
 * ハンドラーは自分のフィルタ一つだけを渡す: `filter({ category })`。残りの 12 個は `undefined` である。ところがトグル関数が `!param` なら<b>消す</b>ように
 * 変わっていた — 元は「値がなければ何もしない」だったが、同じ値を再度押して切る機能を付けるときに「空なら消す」に統合された。そのため、`?category=3` で
 * 色を選ぶと `category` が消えて全カテゴリが出る、検索語を入れてカテゴリを選ぶと検索語が消える、ブランド・サイズ・価格…何を押しても<b>それ一つだけが残る</b>。
 * ユーザーには「カテゴリを選んだのにフィルタが効かない」と見えた — カテゴリは一瞬かかって次のクリックで外れるからである。
 * 規則: <b>`undefined`</b> — 言及していない。<b>触らない。</b><b>空の値</b>(`{}`・`""`・`null`)— 「このフィルタを外して」。消す(searchHandler・sortHandler が `{}` を、
 * 複数選択の最後の解除が `{}` を渡す)。<b>同じ値</b> — 再度押した。切る。<b>違う値</b> — 入れる。カテゴリが<b>別のものに変わると</b>サブカテゴリは消す —
 * そのサブカテゴリは前の親のものなので、新しい親と AND で結ばれると何も出ない。`page` は常に消す。ページ番号は React Query の pageParam が持ち、
 * URL に残るとキャッシュキーを分けるだけである(以前のページネーションの名残)。
 */
/**
 * 하위 카테고리를 눌렀을 때의 변경.
 *
 * <p>🐛 2026-10-26 운영: 카테고리(consumer electronics)를 고른 채 옆 메뉴에서 <b>다른 카테고리의 하위</b>(food › dairy products)를
 * 누르면 `subCategory` 만 바뀌어 두 조건이 AND 로 묶였다 — 맞는 상품이 0 인데 서버가 카테고리를 끝까지 훑어 5초 뒤 503
 * ("サーバーが一時的に応答していません"), 화면의 카테고리 메뉴까지 사라졌다. 이제 하위를 누르면 상위도 그 하위의 상위로 바꾼다
 * (같은 상위면 하위만 — 다시 누르면 꺼진다). 상위가 바뀔 때 하위를 지우는 규칙(applyFilter)의 반대 방향이다.
 *
 * @param {URLSearchParams} searchParams 지금 URL
 * @param {string|number} subCategory 누른 하위 id
 * @param {string|number|undefined} parentCategory 그 하위의 상위 id
 *
 * <p>[JA] サブカテゴリを押したときの変更。🐛 2026-10-26 本番: カテゴリ(consumer electronics)を選んだまま横のメニューで<b>別カテゴリのサブカテゴリ</b>(food › dairy products)を
 * 押すと `subCategory` だけが変わり二つの条件が AND で結ばれた — 合う商品は 0 なのにサーバーがカテゴリを最後まで走査して 5 秒後に 503
 * (「サーバーが一時的に応答していません」)、画面のカテゴリメニューまで消えた。今はサブカテゴリを押すと親もそのサブカテゴリの親に変える
 * (同じ親ならサブカテゴリだけ — 再度押すと切れる)。親が変わるときにサブカテゴリを消す規則(applyFilter)の逆方向である。
 */
export function subCategoryChanges(searchParams, subCategory, parentCategory) {
    const changes = { subCategory };
    if (parentCategory !== undefined && parentCategory !== null
            && String(parentCategory) !== searchParams.get("category")) {
        changes.category = parentCategory;
    }
    return changes;
}

export function applyFilter(searchParams, changes = {}) {
    const isEmptyValue = (v) =>
        v === null || v === "" || (typeof v === "object" && Object.keys(v).length === 0);

    const before = searchParams.get("category");

    for (const name of FILTER_PARAMS) {
        if (!(name in changes) || changes[name] === undefined) {
            continue;   // 언급하지 않은 필터는 그대로 둔다 ／ [JA] 言及していないフィルタはそのままにする
        }
        const value = changes[name];
        if (isEmptyValue(value)) {
            searchParams.delete(name);
        } else if (searchParams.get(name) === String(value)) {
            searchParams.delete(name);   // 같은 값을 다시 누르면 해제 ／ [JA] 同じ値を再度押すと解除
        } else {
            searchParams.set(name, String(value));
        }
    }

    const after = searchParams.get("category");
    if (before !== after && after !== null && !("subCategory" in changes)) {
        searchParams.delete("subCategory");
    }

    searchParams.delete("page");
    return searchParams;
}

const Browse = ({ }) => {

    // loader 가 가져온 1페이지. 아래 useInfiniteQuery 의 initialData 로 들어가므로
    // 첫 화면에서 같은 요청을 두 번 보내지 않는다.
    // [JA] loader が取得した 1 ページ目。下の useInfiniteQuery の initialData に入るので、
    //      最初の画面で同じリクエストを二回送らない。
    const initialPage = useLoaderData();

    const { t } = useTranslation();


    const [searchParams, setSearchParams] = useSearchParams();
    // 목록 카드가 가격대에 맞는 스타일부터 보여 주게(ProductCard) — `price=50_100` · `500_`(이상) · `_50`(이하)
    // [JA] 一覧カードが価格帯に合うスタイルから見せるように(ProductCard)— `price=50_100` · `500_`(以上)· `_50`(以下)
    const priceParamForCards = searchParams.get("price") ?? "";
    const priceFilterForCards = useMemo(() => {
        if (!priceParamForCards) return undefined;
        const [low, high] = splitPriceParam(priceParamForCards);
        return { low, high };
    }, [priceParamForCards]);
    const navigate = useNavigate();

    // 캐시 키이자 queryFn 이 읽을 값. URLSearchParams 객체는 렌더마다 참조가 달라질 수
    // 있어 의존성으로 쓰면 안 된다 — 문자열로 굳혀서 쓴다.
    // [JA] キャッシュキーであり queryFn が読む値。URLSearchParams オブジェクトはレンダーごとに参照が変わり得るので
    //      依存として使ってはならない — 文字列に固めて使う。
    const queryString = searchParams.toString();

    // 필터를 바꾸면 URL 이 바뀌고 라우터 loader 가 다시 돈다. 그 동안 화면에 남아 있는
    // 목록은 **바뀌기 전 결과**다(docs/43 D5). 그 사실을 화면이 말하게 한다.
    // [JA] フィルタを変えると URL が変わりルーターの loader が再実行される。その間画面に残っている
    //      一覧は**変わる前の結果**である(docs/43 D5)。その事実を画面に語らせる。
    const isFiltering = useNavigation().state === "loading";

    /**
     * 무한 스크롤.
     *
     * <p>예전에는 MUI `Pagination` 이었고, `hook/hooks.js` 의 `useInfiniteScroll` 은
     * <b>정의만 있고 부르는 곳이 한 군데도 없는 죽은 코드</b>였다(`grep` 으로 확인).
     * 즉 무한 스크롤은 "제대로 동작하지 않았다"가 아니라 <b>연결된 적이 없다.</b>
     *
     * <p>`throwOnError` 를 여기서만 끈다. 전역 QueryClient 는 `throwOnError: true` 라
     * (util/api.js) 2페이지 요청이 한 번 실패하면 예외가 ErrorBoundary 까지 올라가
     * <b>이미 보고 있던 1페이지까지 통째로 사라진다.</b> 더 불러오기 실패는 그 자리에
     * 다시 시도 버튼으로 보여 주는 편이 맞다.
     *
     * <p>[JA] 無限スクロール。以前は MUI `Pagination` で、`hook/hooks.js` の `useInfiniteScroll` は<b>定義だけあって呼ぶところが一つもない死んだコード</b>だった
     * (`grep` で確認)。つまり無限スクロールは「正しく動かなかった」のではなく<b>一度もつながったことがない。</b>`throwOnError` をここでだけ切る。
     * グローバルの QueryClient は `throwOnError: true` なので(util/api.js)2 ページ目のリクエストが一度失敗すると例外が ErrorBoundary まで上がり、
     * <b>すでに見ていた 1 ページ目まで丸ごと消える。</b>追加読み込みの失敗はその場に再試行ボタンで見せるほうが正しい。
     */
    const {
        data,
        error,
        fetchNextPage,
        hasNextPage,
        isFetchingNextPage,
    } = useInfiniteQuery({
        queryKey: ["browse", queryString],
        queryFn: ({ pageParam }) =>
            fetchBrowsePage(new URLSearchParams(queryString), pageParam),
        initialPageParam: 1,
        getNextPageParam: nextPageParam,
        initialData: initialPage
            ? { pages: [initialPage], pageParams: [1] }
            : undefined,
        // loader 가 방금 같은 것을 가져왔다. 마운트 직후 곧바로 다시 부르지 않는다.
        // [JA] loader が今同じものを取得した。マウント直後にすぐ呼び直さない。
        staleTime: 60_000,
        throwOnError: false,
    });

    // `data?.pages ?? []` 를 그대로 쓰면 data 가 없을 때 렌더마다 새 배열이 나와
    // 아래 useMemo 의 의존성이 매번 바뀐다.
    // [JA] `data?.pages ?? []` をそのまま使うと data がないときレンダーごとに新しい配列ができ、
    //      下の useMemo の依存が毎回変わる。
    const pages = useMemo(() => data?.pages ?? [], [data]);

    // 필터 메뉴(카테고리·색상·사이즈…)는 매 페이지 응답에 똑같이 실려 온다. 1페이지
    // 것만 쓴다. loader 가 null 을 돌려줄 수 있으므로(네트워크 실패) 전부 기본값을 둔다 —
    // 예전에는 `productsDB.categories` 가 그대로 TypeError 를 냈다.
    // [JA] フィルタメニュー(カテゴリ・色・サイズ…)は毎ページの応答に同じものが載ってくる。1 ページ目の
    //      ものだけを使う。loader が null を返し得るので(ネットワーク失敗)すべてに既定値を置く —
    //      以前は `productsDB.categories` がそのまま TypeError を出していた。
    const firstPage = pages[0] ?? {};
    const categories = firstPage.categories ?? [];
    const subCategories = firstPage.subCategories ?? [];
    const details = firstPage.details ?? [];
    const brandsDb = firstPage.brandsDB ?? [];
    const sizes = firstPage.sizes ?? [];
    const colors = firstPage.colors ?? [];
    const totalProducts = firstPage.totalProducts ?? 0;

    let stylesDb = filterArray(details, "Style");
    let materialsDb = filterArray(details, "Material");
    let styles = removeDublicates(stylesDb);
    let materials = removeDublicates(materialsDb);
    let brands = removeDublicates(brandsDb);

    /**
     * 지금까지 불러온 페이지를 하나로 잇는다.
     *
     * <p>정렬을 <b>여기서</b> 한다. 예전에는 loader 안에서 그 페이지의 10개만 정렬했다.
     * 페이지네이션일 때는 한 번에 한 페이지만 보이니 그럴듯해 보였지만, 무한 스크롤에서는
     * 페이지 경계마다 가격이 다시 처음으로 튀어 오르는 게 그대로 보인다. 이어 붙인
     * 전체를 정렬한다.
     *
     * <p>그래도 이건 <b>불러온 것 안에서의 정렬</b>이다. 진짜 해법은 서버가 정렬해서
     * 페이징하는 것이다(catalog-service SearchService 는 아직 정렬 파라미터를 받지 않는다).
     * 정렬을 바꾸면 URL 의 sort 파라미터가 바뀌고 → queryKey 가 바뀌어 1페이지부터
     * 다시 받으므로, 적어도 "정렬이 이전 목록에만 적용되는" 상태로 남지는 않는다.
     *
     * <p>[JA] これまで読み込んだページを一つにつなぐ。並べ替えを<b>ここで</b>行う。以前は loader の中でそのページの 10 件だけを並べ替えていた。
     * ページネーションのときは一度に 1 ページしか見えないのでそれらしく見えたが、無限スクロールではページの境目ごとに価格が先頭に跳ね戻るのが
     * そのまま見える。つなげた全体を並べ替える。それでもこれは<b>読み込んだ範囲内での並べ替え</b>である。本当の解決はサーバーが並べ替えてページングすること
     * (catalog-service SearchService はまだ並べ替えパラメータを受け取らない)。並べ替えを変えると URL の sort パラメータが変わり → queryKey が変わって
     * 1 ページ目から受け直すので、少なくとも「並べ替えが前の一覧にだけ適用される」状態には残らない。
     */
    const sortQuery = searchParams.get("sort") ?? "";
    const products = useMemo(() => {
        const seen = new Set();
        const merged = [];
        for (const page of pages) {
            for (const product of page?.product?.content ?? []) {
                // offset 페이징은 정렬이 흔들리면 같은 상품을 두 페이지에 걸쳐 돌려줄 수
                // 있다. key 가 겹치면 React 가 경고하고 목록이 어긋나므로 걸러 낸다.
                // [JA] offset ページングは並びが揺れると同じ商品を二つのページにまたがって返すことが
                //      ある。key が重なると React が警告し一覧がずれるので取り除く。
                if (product?.id != null && seen.has(product.id)) continue;
                if (product?.id != null) seen.add(product.id);
                merged.push(product);
            }
        }
        return sortProducts(merged, sortQuery);
    }, [pages, sortQuery]);

    // 감시 대상이 화면에 들어오면 다음 페이지를 부른다. 이미 받는 중이면 hasNextPage 를
    // 내려 중복 요청을 막는다.
    // [JA] 監視対象が画面に入ると次のページを呼ぶ。すでに取得中なら hasNextPage を
    //      下げて重複リクエストを防ぐ。
    const sentinelRef = useInfiniteScroll({
        hasNextPage: Boolean(hasNextPage) && !isFetchingNextPage,
        fetchNextPage,
    });


    /**
     * 필터 하나를 바꾼다. 규칙은 {@link applyFilter} 에 있다 — 거기가 테스트 대상이다.
     *
     * <p>핸들러는 자기 필터 <b>하나만</b> 넘긴다(`filter({ category })`). 나머지는 건드리지
     * 않는다 — 카테고리를 고른다고 검색어·색상·브랜드가 지워지면 안 된다.
     *
     * <p>[JA] フィルタを一つ変える。規則は {@link applyFilter} にある — そこがテスト対象である。ハンドラーは自分のフィルタ<b>一つだけ</b>を渡す
     * (`filter({ category })`)。残りは触らない — カテゴリを選んだからといって検索語・色・ブランドが消えてはならない。
     */
    const filter = (changes) => {
        // 🐛 2026-10-24 운영: "전체 해제하면 푸드만, 푸드를 누르면 책만" — 화면이 늘 **바로 앞 조건의 결과**를 그렸다. 예전에는
        //    useSearchParams() 의 객체를 제자리에서 바꿨다. 그 객체는 지금 주소의 것이라, 이동(loader)이 끝나기 전 다시 그릴 때
        //    queryKey 는 새 조건인데 initialData(useLoaderData)는 아직 앞 조건의 결과 — 새 키에 앞 결과가 담겨 staleTime 동안 그대로였다.
        //    사본을 바꿔 넘긴다(browse.navigation.test.jsx).
        // [JA] 以前は useSearchParams() のオブジェクトをその場で書き換えていた。遷移完了前の再描画でクエリキーは新条件、initialData は
        //      前の条件の結果 — 新しいキーに前の結果が入った。コピーを書き換えて渡す。
        const next = new URLSearchParams(searchParams);
        applyFilter(next, changes);
        setSearchParams(next);
    };

    const searchHandler = (search) => {
        if (search === "") {
            filter({ search: {} });
        } else {
            filter({ search });
        }
    };
    const categoryHandler = (category) => {
        filter({ category });
    };
    /**
     * 하위 카테고리 필터.
     *
     * <p>🐛 이 핸들러는 <b>없었다.</b> 하위 카테고리 목록은 화면에 그려지는데 클릭
     * 핸들러도, URL 파라미터도, 서버 쪽 조건도 없었다 — 기능이 어디에도 존재한 적이
     * 없다(ParentCategory 주석 참조).
     *
     * <p>[JA] サブカテゴリフィルタ。🐛 このハンドラーは<b>なかった。</b>サブカテゴリ一覧は画面に描かれるのに、クリックハンドラーも URL パラメータも
     * サーバー側の条件もなかった — 機能がどこにも存在したことがない(ParentCategory のコメント参照)。
     */
    const subCategoryHandler = (subCategory, parentCategory) => {
        filter(subCategoryChanges(searchParams, subCategory, parentCategory));
    };
    const brandHandler = (brand) => {
        filter({ brand });
    };
    const styleHandler = (style) => {
        filter({ style });
    };
    const sizeHandler = (size) => {
        filter({ size });
    };
    const colorHandler = (color) => {
        filter({ color });
    };
    const materialHandler = (material) => {
        filter({ material });
    };
    const genderHandler = (gender) => {

        filter({ gender });
    };


    /**
     * 가격 슬라이더/입력용 디바운스.
     *
     * <p>예전에는 `priceHandler` 안에서 `debounce(...)` 를 <b>호출할 때마다 새로</b> 만들었다.
     * 디바운스는 이전 타이머를 기억하는 클로저에 의존하는데, 매번 새 클로저를 만들면 각
     * 호출이 자기만의 타이머를 갖는다 — 취소할 이전 타이머가 없으니 <b>디바운스가 전혀
     * 동작하지 않았다.</b> 가격을 타이핑하면 글자 수만큼 검색 요청이 나갔다.
     *
     * <p>타이머 id 를 ref 에 두어 렌더가 바뀌어도 같은 타이머를 취소하도록 했다.
     *
     * <p>[JA] 価格スライダー / 入力用のデバウンス。以前は `priceHandler` の中で `debounce(...)` を<b>呼ぶたびに新しく</b>作っていた。
     * デバウンスは前のタイマーを覚えるクロージャに依存するが、毎回新しいクロージャを作ると各呼び出しが自分だけのタイマーを持つ —
     * 取り消す前のタイマーがないので<b>デバウンスがまったく動いていなかった。</b>価格をタイプすると文字数だけ検索リクエストが出た。
     * タイマー id を ref に置き、レンダーが変わっても同じタイマーを取り消すようにした。
     */
    const priceTimer = useRef(null);

    useEffect(() => () => clearTimeout(priceTimer.current), []);

    const priceHandler = (price, type, delay = 300) => {
        const priceQuery = searchParams.get('price')?.split("_") || [];
        const min = priceQuery[0] || "";
        const max = priceQuery[1] || "";
        const newPrice = type === "min" ? `${price}_${max}` : `${min}_${price}`;

        clearTimeout(priceTimer.current);
        priceTimer.current = setTimeout(() => filter({ price: newPrice }), delay);
    };

    const multiPriceHandler = (min, max) => {
        filter({ price: `${min}_${max}` });
    };

    const shippingHandler = (shipping) => {
        filter({ shipping });
    };
    const ratingHandler = (rating) => {
        filter({ rating });
    };
    const sortHandler = (sort) => {
        if (sort === "") {
            filter({ sort: {} });
        } else {
            filter({ sort });
        }
    };
    // ── 페이지네이션(보존) ──────────────────────────────────────────────────
    // 무한 스크롤로 대체했다. 지우지 않고 남겨 둔다.
    // const paginationCount = Math.ceil(totalProducts / PAGE_SIZE);
    // const pageHandler = (e, page) => {
    //     filter({ page });
    // };
    // ────────────────────────────────────────────────────────────────────────
    // [JA] ── ページネーション(保存) ── 無限スクロールに置き換えた。消さずに残しておく。

    const replaceQuery = (queryName, value) =>
        toggleInMultiValue(searchParams.get(queryName), value);
    // ----------------------------------------
    const [scrollY, setScrollY] = useState(0);    
    const [loaded, setLoaded] = useState(false);
    const heightRef = useRef(0);
    const headerRef = useRef(null);
    const el = useRef(null);
    
    useEffect(() => {
        // scrollY 는 "헤더 높이를 넘었는가" 하나를 판정하는 데만 쓰인다(아래 두 className).
        // 예전에는 스크롤 이벤트마다 setScrollY 로 페이지 전체를 다시 그렸다 — 트랙패드 한 번에
        // 수십 번. 프레임당 한 번(rAF)으로 묶고, 값은 픽셀이 아니라 **판정 결과가 바뀔 때만**
        // 저장한다. 그러면 스크롤 중 대부분의 프레임에서 setState 자체가 일어나지 않는다.
        // [JA] scrollY は「ヘッダーの高さを超えたか」一つを判定するのにだけ使う(下の二つの className)。
        //      以前はスクロールイベントごとに setScrollY でページ全体を再描画していた — トラックパッド一回で
        //      数十回。フレームごとに一回(rAF)にまとめ、値はピクセルではなく**判定結果が変わったときだけ**
        //      保存する。そうするとスクロール中の大半のフレームで setState 自体が起きない。
        let frame = 0;
        const handleScroll = () => {
            if (frame) return;                       // 이번 프레임에 이미 예약됨 ／ [JA] このフレームはすでに予約済み
            frame = window.requestAnimationFrame(() => {
                frame = 0;
                const passed = window.scrollY >= heightRef.current;
                setScrollY((prev) => {
                    const prevPassed = prev >= heightRef.current;
                    return prevPassed === passed ? prev : window.scrollY;
                });
            });
        };
        window.addEventListener("scroll", handleScroll, { passive: true });

        heightRef.current = headerRef.current?.offsetHeight + el.current?.offsetHeight + 150;
        setScrollY(window.scrollY);
        setLoaded(true);

        return () => {
            window.removeEventListener("scroll", handleScroll);
            if (frame) window.cancelAnimationFrame(frame);
        };
    }, []);

    return (
        <>
            <Header title={t("browse.title")} searchHandler={searchHandler} />
            <div  className="max-w-screen-2xl mx-auto bg-slate-100 p-1 md:p-6 gap-2">
                <div ref={headerRef}>
                    <div className="flex items-center text-sm">
                        <span className="text-slate-700">{t("browse.home")}</span>
                        <ChevronRightIcon className="w-4 h-4 mx-1 fill-slate-600 " />
                        <span className="text-slate-700">{t("browse.title")}</span>
                        {/* 없으면 null 이다 — `!== ""` 는 언제나 참이라 빈 화살표가 남았다. */}
                        {/* [JA] なければ null である — `!== ""` は常に真なので空の矢印が残っていた。 */}
                        {searchParams.get('category') && (
                            <>
                                <ChevronRightIcon className="w-4 h-4 mx-1 fill-slate-600 " />
                                <span className="text-slate-700">
                                    {
                                        categories.find(
                                            (x) =>
                                                x.id === searchParams.get('category')
                                        )?.name
                                    }
                                </span>
                            </>
                        )}
                    </div>

                    <div
                        ref={el}
                        className="mt-2 flex flex-wrap gap-3 flex-wrap"
                    >
                        {categories.map((c) => (
                            <span
                                onClick={() => categoryHandler(c.id)}
                                className={`cursor-pointer flex items-center justify-center w-40 md:w-56 h-10 border bg-white rounded  transition-all duration-300 hover:bg-amazon-blue_light hover:text-white hover:scale-95 hover:border-amazon-blue_dark`}
                                key={c.id}
                            >
                                {c.name}
                            </span>
                        ))}
                    </div>
                </div>

                <div  className="relative mt-4 grid grid-cols-5 gap-1 md:gap-5">
                    <div
                        className={`h-[680px] col-span-5 md:col-span-1 flex flex-col md:items-center  overflow-y-auto overflow-x-hidden 
                            ${scrollY >= heightRef.current 
                            ? "md:fixed md:w-[274px] md:top-2"
                            : ""
                            }`}
                    >
                        <button
                            onClick={() => navigate("/browse")}
                            className={`flex items-center justify-center w-56 md:w-full py-2 rounded transition-all duration-300 bg-amazon-blue_light text-white hover:scale-95 border-amazon-blue_dark`}
                        >
                            {t("browse.filter.clear_all", { count: Array.from(searchParams).length })}
                        </button>
                        <CategoriesFilter
                            categories={categories}
                            subCategories={subCategories}
                            categoryHandler={categoryHandler}
                            subCategoryHandler={subCategoryHandler}
                            replaceQuery={replaceQuery}
                        />
                        <SizesFilter
                            sizes={sizes}
                            sizeHandler={sizeHandler}
                            replaceQuery={replaceQuery}
                        />
                        <ColorsFilter
                            colors={colors}
                            colorHandler={colorHandler}
                            replaceQuery={replaceQuery}
                        />
                        <BrandsFilter
                            brands={brands}
                            brandHandler={brandHandler}
                            replaceQuery={replaceQuery}
                        />
                        <StylesFilter
                            styles={styles}
                            styleHandler={styleHandler}
                            replaceQuery={replaceQuery}
                        />
                        <MaterialsFilter
                            materials={materials}
                            materialHandler={materialHandler}
                            replaceQuery={replaceQuery}
                        />
                        <GenderFilter
                            genderHandler={genderHandler}
                            replaceQuery={replaceQuery}
                        />
                    </div>

                    <div 
                        className={`${scrollY >= heightRef.current  ? "md:block" : "hidden"
                            } max-md:hidden md:col-span-1`}
                    />
                    <div className="col-span-5 md:col-span-4 flex flex-col content-start">
                        <HeadingFilter
                            priceHandler={priceHandler}
                            multiPriceHandler={multiPriceHandler}
                            shippingHandler={shippingHandler}
                            ratingHandler={ratingHandler}
                            sortHandler={sortHandler}
                            searchParams={searchParams}
                        />
                        {/*
                          * 필터를 바꾸는 동안(= 라우터가 loader 를 기다리는 동안) 지금 목록을
                          * **흐리게** 둔다(docs/43 D5).
                          *
                          * 🔴 이 앱의 필터는 URL 을 바꾸고, 그러면 라우터 loader 가 다시 돈다.
                          *    데이터 라우터는 로더가 끝나야 화면을 바꾸므로 그 사이 목록은
                          *    **바뀌기 전 결과**다. 아무 표시가 없으면 사용자는 필터가 안 먹은
                          *    줄 알고 다시 누른다.
                          *
                          * 목록을 지우거나 스켈레톤으로 바꾸지 않는다 — 이미 읽고 있던 결과가
                          * 사라지고 레이아웃이 튄다. 흐리게 하고 클릭만 막으면 "이건 곧 바뀔
                          * 값" 이라는 뜻이 전달되면서 읽던 것은 그대로 남는다.
                          */}
                        {/* [JA] フィルタを変えている間(= ルーターが loader を待つ間)今の一覧を**薄く**しておく(docs/43 D5)。🔴 このアプリのフィルタは URL を変え、するとルーターの loader が再実行される。データルーターはローダーが終わってから画面を変えるので、その間の一覧は**変わる前の結果**である。何の表示もなければユーザーはフィルタが効かなかったと思って再度押す。一覧を消したりスケルトンに替えたりはしない — すでに読んでいた結果が消えレイアウトが跳ねる。薄くしてクリックだけ防げば「これはもうすぐ変わる値」という意味が伝わり、読んでいたものはそのまま残る。 */}
                        <div
                            className={`mt-6 flex flex-wrap items-start gap-4 transition-opacity duration-200 ${
                                isFiltering ? "opacity-40 pointer-events-none" : ""
                            }`}
                            aria-busy={isFiltering}
                        >
                            {products.map((product) => (
                                <ProductCard
                                    product={product}
                                    // 가격대를 바꾸면 카드를 새로 — 맞은 스타일부터 다시 고른다(ProductCard 의 priceFilter)
                                    // [JA] 価格帯を変えるとカードを作り直す — 合うスタイルから選び直す(ProductCard の priceFilter)
                                    key={`${product.id}|${priceParamForCards}`}
                                    priceFilter={priceFilterForCards}
                                />
                            ))}
                        </div>

                        {/*
                          * 첫 진입이라 보여 줄 목록이 아직 없을 때만 스켈레톤을 그린다.
                          * 필터 변경(위)에는 쓰지 않는다 — 그때는 보여 줄 이전 결과가 있다.
                          */}
                        {/* [JA] 初回表示で見せる一覧がまだないときだけスケルトンを描く。フィルタ変更(上)には使わない — そのときは見せる以前の結果がある。 */}
                        {products.length === 0 && isFiltering && <ProductGridSkeleton />}

                        {products.length === 0 && !isFetchingNextPage && !isFiltering && (
                            <div className="w-full my-10 text-center text-slate-500">
                                {t("browse.list.empty")}
                            </div>
                        )}

                        {/*
                          * 감시 대상(sentinel). 이 div 가 화면에 들어오면 useInfiniteScroll 의
                          * IntersectionObserver 가 다음 페이지를 부른다.
                          *
                          * 목록 **안**이 아니라 바로 뒤에 둔다. 마지막 카드에 ref 를 달면
                          * 카드가 바뀔 때마다 observer 를 다시 붙여야 하고, 목록이 비면 붙일
                          * 곳 자체가 없어진다.
                          */}
                        {/* [JA] 監視対象(sentinel)。この div が画面に入ると useInfiniteScroll の IntersectionObserver が次のページを呼ぶ。一覧の**中**ではなくすぐ後ろに置く。最後のカードに ref を付けると、カードが変わるたびに observer を付け直さなければならず、一覧が空になると付ける場所自体がなくなる。 */}
                        <div ref={sentinelRef} className="w-full h-1" aria-hidden="true" />

                        {isFetchingNextPage && (
                            <div className="w-full my-6 flex justify-center">
                                <DotLoader loading={true} color="#febd69" size={40} />
                            </div>
                        )}

                        {/* 더 불러오기 실패. 전역 설정과 달리 화면을 날리지 않고(throwOnError:false)
                            여기서만 알린다 — 이미 본 목록은 그대로 남는다. */}
                        {/* [JA] 追加読み込みの失敗。グローバル設定と違い画面を飛ばさず(throwOnError:false)ここでだけ知らせる — すでに見た一覧はそのまま残る。 */}
                        {error && (
                            <div className="w-full my-6 flex flex-col items-center gap-2">
                                <span className="text-red-500">
                                    {t("browse.list.load_more_failed")}
                                </span>
                                <button
                                    type="button"
                                    onClick={() => fetchNextPage()}
                                    className="px-4 py-2 rounded bg-amazon-blue_light text-white hover:scale-95 transition-all duration-300"
                                >
                                    {t("browse.list.retry")}
                                </button>
                            </div>
                        )}

                        {!hasNextPage && products.length > 0 && (
                            <div className="w-full my-6 text-center text-sm text-slate-500">
                                {t("browse.list.end", { count: products.length })}
                            </div>
                        )}

                        {/* ── 페이지네이션(보존) ─────────────────────────────────────
                          * 무한 스크롤로 대체했다. 지우지 않고 남겨 둔다. 되돌리려면
                          *   ① 파일 맨 위의 `import { Pagination } from "@mui/material";`
                          *   ② `pageHandler` / `paginationCount` (위쪽 주석 블록)
                          *   ③ filter() 안의 `toggleParam('page', page)`
                          * 를 함께 살리고, 위의 sentinel/스피너/"모두 보셨습니다"를 지운다.
                          *
                          * <div className="w-full my-4 flex items-end justify-end">
                          *     <Pagination
                          *         count={paginationCount}
                          *         variant="outlined"
                          *         defaultPage={Number(searchParams.get('page')) || 1}
                          *         onChange={pageHandler}
                          *         size="large"
                          *     />
                          * </div>
                          * ──────────────────────────────────────────────────────── */}
                        {/* [JA] ── ページネーション(保存) ── 無限スクロールに置き換えた。消さずに残しておく。戻すには ① ファイル先頭の Pagination の import ② `pageHandler` / `paginationCount`(上のコメントブロック)③ filter() の中の `toggleParam('page', page)` を一緒に生かし、上の sentinel / スピナー /「すべてご覧になりました」を消す。 */}
                    </div>
                </div>
            </div>
        </>
    );
};

export default Browse;

/* ──────────────────────────────────────────────────────────────────────────
 * 검색 요청 / 정렬
 *
 * 예전에는 이 전부가 아래 `loader` 안에 들어 있었다. 무한 스크롤은 2페이지부터를
 * **컴포넌트에서** 불러야 하므로, loader 와 useInfiniteQuery 가 같은 함수를 쓰도록
 * 모듈 스코프로 끌어올렸다. 파라미터를 만드는 규칙이 두 군데로 갈라지면 1페이지와
 * 2페이지가 다른 조건으로 검색되는, 눈으로는 알아채기 어려운 버그가 된다.
 *
 * <p>[JA] 検索リクエスト / 並べ替え。以前はこのすべてが下の `loader` の中にあった。無限スクロールは 2 ページ目以降を**コンポーネントから**呼ばなければならないので、
 * loader と useInfiniteQuery が同じ関数を使うようにモジュールスコープへ引き上げた。パラメータを作る規則が二か所に分かれると、1 ページ目と
 * 2 ページ目が別の条件で検索される、目では気づきにくいバグになる。
 * ────────────────────────────────────────────────────────────────────────── */

/**
 * 여러 개를 고를 수 있는 필터(`색상=Red_Blue`)에서 값 하나를 켜고 끈다.
 *
 * @returns {{result: string|{}, active: boolean}}
 *   `result` 는 새 쿼리 값이고, 마지막 하나를 빼면 `{}` 다(= 이 필터를 지운다는 뜻으로
 *   `filter()` 의 `toggleParam` 이 읽는 값). `active` 는 지금 켜져 있는가.
 *
 * <h3>🐛 예전 구현은 문자열을 <b>정규식</b>으로 읽었다</h3>
 *
 * <p>`existedQeury.search(value)` — {@link String.prototype.search} 의 인자는
 * <b>RegExp 다.</b> 문자열을 주면 브라우저가 정규식으로 컴파일한다. 그래서:
 *
 * <ul>
 *   <li>메타문자가 든 값이 그대로 터진다 — 브랜드 `"C++"` 는
 *       <code>SyntaxError: Nothing to repeat</code> 로 <b>필터 렌더 중 화면이 죽는다.</b>
 *       (실측: `"C++"` · `"*Star"` · `"?"` 는 던지고, `"A+B"` 는 던지지 않는다 —
 *       터지는 값과 아닌 값이 눈으로 구분되지 않는다는 점이 더 나쁘다.)
 *       `"C.C"` 는 조용히 틀린다: `.` 이 아무 글자나 맞춰서 `"CXC"` 도 선택됨으로 읽는다</li>
 *   <li>부분 일치를 <b>선택됨</b>으로 읽었다 — `색상=Green` 일 때 `Gre` 로 시작하는
 *       다른 값이 켜진 것처럼 보인다</li>
 *   <li>빈 문자열은 언제나 0 이라 늘 "켜짐"이다</li>
 * </ul>
 *
 * <p>값은 `_` 로 이어 붙인 <b>목록</b>이다. 목록으로 다루면 위의 셋이 한꺼번에 사라진다 —
 * 문자열 검색을 할 이유가 처음부터 없었다.
 *
 * <p>[JA] 複数を選べるフィルタ(`色=Red_Blue`)で値を一つ入れたり切ったりする。`result` は新しいクエリ値で、最後の一つを外すと `{}` になる
 * (= このフィルタを消すという意味で `filter()` の `toggleParam` が読む値)。`active` は今入っているか。
 * 🐛 以前の実装は文字列を<b>正規表現</b>として読んでいた。`existedQeury.search(value)` — {@link String.prototype.search} の引数は<b>RegExp である。</b>
 * 文字列を渡すとブラウザが正規表現にコンパイルする。そのため: メタ文字を含む値がそのまま落ちる — ブランド `"C++"` は SyntaxError: Nothing to repeat で
 * <b>フィルタ描画中に画面が死ぬ。</b>(実測: `"C++"` · `"*Star"` · `"?"` は投げ、`"A+B"` は投げない — 落ちる値とそうでない値が目で区別できないのがさらに悪い。)
 * `"C.C"` は静かに間違う: `.` が任意の文字に合うので `"CXC"` も選択済みと読む。部分一致を<b>選択済み</b>と読んでいた — `色=Green` のとき `Gre` で始まる
 * 別の値が入っているように見える。空文字列は常に 0 なのでいつも「オン」である。値は `_` でつないだ<b>リスト</b>である。リストとして扱えば上の三つが
 * 一度に消える — 文字列検索をする理由は最初からなかった。
 */
export function toggleInMultiValue(existing, value) {
    const target = String(value);

    if (!existing) {
        return { result: target, active: false };
    }

    const parts = existing.split("_");
    const active = parts.includes(target);

    if (!active) {
        return { result: `${existing}_${target}`, active: false };
    }

    const rest = parts.filter((p) => p !== target);
    // 마지막 하나를 껐다 = 이 필터가 없어진다. 빈 문자열을 URL 에 남기면
    // `?color=` 가 되고, 서버는 그것을 "빈 값으로 검색"으로 읽는다.
    // [JA] 最後の一つを切った = このフィルタがなくなる。空文字列を URL に残すと
    //      `?color=` になり、サーバーはそれを「空の値で検索」と読む。
    return { result: rest.length > 0 ? rest.join("_") : {}, active: true };
}

function createRegex(data, regex) {
    if (data.length > 1) {
        for (let i = 1; i < data.length; i++) {
            regex += `|^${data[i]}`;
        }
    }
    return regex;
}

/**
 * URL 의 검색 조건을 catalog-service 가 받는 파라미터로 바꾼다.
 *
 * <p>`page` 는 URL 이 아니라 **인자로** 받는다. 무한 스크롤의 페이지 번호는 React Query 의
 * pageParam 이 갖는다.
 *
 * <p>빈 값을 `{}` 로 두는 것과 `null` 로 두는 것이 섞여 있는데, 이는 기존 동작 그대로다.
 * axios 는 빈 객체를 쿼리스트링에 아무것도 싣지 않고 null 은 건너뛰므로 결과는 같다 —
 * 서버 계약을 건드리지 않으려고 표현을 바꾸지 않았다.
 *
 * <p>[JA] URL の検索条件を catalog-service が受け取るパラメータに変える。`page` は URL ではなく**引数で**受け取る。無限スクロールのページ番号は
 * React Query の pageParam が持つ。空の値を `{}` にするものと `null` にするものが混ざっているが、これは既存の動作のままである。
 * axios は空オブジェクトをクエリ文字列に何も載せず null は飛ばすので結果は同じ — サーバーとの契約に触れないよう表現を変えなかった。
 */
export function buildSearchRequest(searchParams, page) {
    const searchParam = searchParams.get("search");
    const categoryParam = searchParams.get("category");
    const subCategoryParam = searchParams.get("subCategory");
    const priceParam = searchParams.get("price");
    const shippingParam = searchParams.get("shipping");
    // ⚠️ 예전에는 `searchParams.get("rate")` 였다. 그런데 URL 에 들어가는 이름은
    // "rating" 이다 — ratingHandler → filter({rating}) → toggleParam('rating', …),
    // HeadingFilter 도 `searchParams.get('rating')` 를 읽어 체크 상태를 그린다.
    // 즉 별점 필터는 체크는 되는데 **검색 조건에는 한 번도 들어간 적이 없었다.**
    // [JA] ⚠️ 以前は `searchParams.get("rate")` だった。ところが URL に入る名前は
    //      "rating" である — ratingHandler → filter({rating}) → toggleParam('rating', …)、
    //      HeadingFilter も `searchParams.get('rating')` を読んでチェック状態を描く。
    //      つまり星評価フィルタはチェックは付くのに**検索条件には一度も入ったことがなかった。**
    const rateParam = searchParams.get("rating");

    const brandParam = searchParams.get("brand");
    const styleParam = searchParams.get("style");
    const sizeParam = searchParams.get("size");
    const colorParam = searchParams.get("color");
    const materialParam = searchParams.get("material");
    const genderParam = searchParams.get("gender");

    const searchQuery = searchParam || "";
    const categoryQuery = categoryParam || "";
    const subCategoryQuery = subCategoryParam || "";
    const priceQuery = priceParam?.split("_") || "";
    const shippingQuery = shippingParam || 0;
    const ratingQuery = rateParam || "";
    // --------------------------------------------------
    const brandQuery = brandParam?.split("_") || "";
    const brandSearchRegex = createRegex(brandQuery, `^${brandQuery[0]}`);
    // --------------------------------------------------
    const styleQuery = styleParam?.split("_") || "";
    const styleSearchRegex = createRegex(styleQuery, `^${styleQuery[0]}`);
    // --------------------------------------------------
    const sizeQuery = sizeParam?.split("_") || "";
    const sizeSearchRegex = createRegex(sizeQuery, `^${sizeQuery[0]}`);
    // --------------------------------------------------
    const colorQuery = colorParam?.split("_") || "";
    const colorSearchRegex = createRegex(colorQuery, `^${colorQuery[0]}`);
    // --------------------------------------------------
    const materialQuery = materialParam?.split("_") || "";
    const materialSearchRegex = createRegex(materialQuery, `^${materialQuery[0]}`);
    // --------------------------------------------------
    const genderQuery = genderParam?.split("_") || "";
    const genderSearchRegex = createRegex(genderQuery, `^${genderQuery[0]}`);
    // --------------------------------------------------
    const search = searchQuery && searchQuery !== "" ? searchQuery : {};
    const category = categoryQuery && categoryQuery !== "" ? categoryQuery : {};
    const subCategory = subCategoryQuery && subCategoryQuery !== "" ? subCategoryQuery : {};
    const style = styleQuery && styleQuery !== "" ? styleSearchRegex : null;
    const size = sizeQuery && sizeQuery !== "" ? sizeSearchRegex : {};
    const color = colorQuery && colorQuery !== "" ? colorSearchRegex : {};
    const brand = brandQuery && brandQuery !== "" ? brandSearchRegex : {};
    const material = materialQuery && materialQuery !== "" ? materialSearchRegex : {};
    const gender = genderQuery && genderQuery !== "" ? genderSearchRegex : {};
    /**
     * 가격 경계 하나.
     *
     * <p>🐛 예전에는 `Number(priceQuery[1]) || 0` 이었다. <b>비어 있는 상한이 0 이 된다.</b>
     * 화면의 "500 이상"(`price=500_`) 과 최소만 입력한 경우가 전부 `500 ~ 0` 이라는
     * 불가능한 범위로 나갔고, 서버는 아무것도 못 찾은 뒤 그 "없음"을 다시 <b>전체 상품</b>
     * 으로 뒤집었다(SearchService 1번 조건). 결과가 "가격대를 골랐더니 전부 나온다"였다.
     *
     * <p>비었으면 <b>보내지 않는다.</b> axios 가 undefined 를 쿼리스트링에서 빼고, 서버는
     * null 을 "그 경계는 없음"으로 읽는다 — 열린 범위가 그대로 표현된다.
     *
     * <p>[JA] 価格の境界一つ。🐛 以前は `Number(priceQuery[1]) || 0` だった。<b>空の上限が 0 になる。</b>画面の「500 以上」(`price=500_`)と最小だけを入力した場合が
     * すべて `500 ~ 0` という不可能な範囲で出て行き、サーバーは何も見つけられなかった後その「なし」を再び<b>全商品</b>にひっくり返した
     * (SearchService 1 番目の条件)。結果は「価格帯を選んだら全部出る」だった。空なら<b>送らない。</b>axios が undefined をクエリ文字列から外し、
     * サーバーは null を「その境界はなし」と読む — 開いた範囲がそのまま表現される。
     */
    const priceBound = (raw) => {
        if (raw === undefined || raw === null || String(raw).trim() === "") return undefined;
        const n = Number(raw);
        return Number.isFinite(n) ? n : undefined;
    };

    const price = {
        lowPrice: priceBound(priceQuery[0]),
        highPrice: priceBound(priceQuery[1]),
    };
    const shipping = shippingQuery && shippingQuery === "0" ? { shipping: 0 } : {};
    const rating = ratingQuery && ratingQuery !== "" ? { rating: Number(ratingQuery) } : {};

    const safePage = Math.max(1, Number(page) || 1);

    return {
        search, category, subCategory, brand, style,
        size, color, material, gender,
        lowPrice: price.lowPrice, highPrice: price.highPrice,
        shipping: shipping.shipping,

        /**
         * 🐛 여기가 `rating,` 이었다 — 숫자가 아니라 <b>객체</b>({rating: 4})를 그대로 실었다.
         * axios 는 그것을 `rating[rating]=4` 로 직렬화하므로 서버에는 `rating` 이라는
         * 파라미터가 <b>아예 도착하지 않는다.</b> 스프링은 float 기본값 0 으로 바인딩하고,
         * `params.getRating() != 0.0f` 는 거짓 — 조건이 붙지 않는다.
         *
         * <p>바로 옆의 `shipping` 은 `shipping.shipping` 으로 풀어 보내고 있었다. 별점만
         * 빠뜨린 것이다. 이름을 `rate` → `rating` 으로 고친 뒤에도(docs/40) 이 한 겹이
         * 남아 있어서 별점 필터는 여전히 동작하지 않았다.
         *
         * <p>[JA] 🐛 ここが `rating,` だった — 数値ではなく<b>オブジェクト</b>({rating: 4})をそのまま載せていた。axios はそれを `rating[rating]=4` とシリアライズするので、
         * サーバーには `rating` というパラメータが<b>まったく届かない。</b>Spring は float の既定値 0 でバインドし、`params.getRating() != 0.0f` は偽 — 条件が付かない。
         * すぐ隣の `shipping` は `shipping.shipping` に展開して送っていた。星評価だけ漏れていたのである。名前を `rate` → `rating` に直した後も(docs/40)この一層が
         * 残っていたので、星評価フィルタは依然として動かなかった。
         */
        rating: rating.rating,
        page: safePage,
        pageSize: PAGE_SIZE,

        /**
         * 필터 메뉴(카테고리·하위 카테고리·색상·사이즈·상세·브랜드)는 <b>첫 페이지에서만</b>
         * 받는다.
         *
         * <p>🐛 예전에는 서버가 매 요청마다 그것들을 함께 실어 보냈고 — 그중 둘은 카테고리
         * 전체와 하위 카테고리 전체를 읽는 `findAll()` 이다 — 화면은 첫 페이지 것만 쓰고
         * 나머지를 버렸다(아래 `firstPage` 참조). 무한 스크롤을 붙인 뒤로는 스크롤할 때마다
         * 쓰지도 않을 카테고리 전체가 따라왔다.
         *
         * <p>[JA] フィルタメニュー(カテゴリ・サブカテゴリ・色・サイズ・詳細・ブランド)は<b>最初のページでだけ</b>受け取る。
         * 🐛 以前はサーバーが毎リクエストそれらを一緒に載せて送り — そのうち二つはカテゴリ全体とサブカテゴリ全体を読む `findAll()` である — 画面は
         * 最初のページのものだけを使い残りを捨てていた(下の `firstPage` 参照)。無限スクロールを付けてからは、スクロールのたびに使いもしないカテゴリ全体が付いてきた。
         */
        includeFilters: safePage === 1,
    };
}

/** 정렬 대상 sku 를 많이 팔린 순으로. 원본을 건드리지 않는다(React Query 캐시다). */
// [JA] 並べ替え対象の sku をよく売れた順に。元を触らない(React Query のキャッシュである)。
function sortSkusBySold(product) {
    if (!Array.isArray(product?.skus)) return product;
    return {
        ...product,
        skus: [...product.skus].sort((a, b) => Number(b.sold) - Number(a.sold)),
    };
}

/**
 * 첫 번째 sku 의 최저/최고 가격.
 *
 * <p>예전 코드는 `sku.sizes.sort(...)` 로 캐시 안의 배열을 <b>제자리에서 뒤집은 뒤</b>
 * `sizes[0].price` 를 읽었다. sizes 가 비어 있으면 그 자리에서 TypeError 였고, skus 가
 * 비어 있으면 정렬 비교 함수 안에서 undefined 를 읽어 터졌다. 읽기만 한다.
 *
 * <p>[JA] 最初の sku の最低 / 最高価格。以前のコードは `sku.sizes.sort(...)` でキャッシュ内の配列を<b>その場で並べ替えた後</b> `sizes[0].price` を読んでいた。
 * sizes が空ならその場で TypeError で、skus が空なら並べ替えの比較関数の中で undefined を読んで落ちた。読むだけにする。
 */
function firstSkuPrice(product, pick) {
    // 🐛 예전에는 정가로 정렬했다 — 카드가 할인가를 보여 주면(ProductCard) 30% 할인된 $70 짜리가 $80 짜리보다
    //    뒤에 섰다. 화면에 보이는 가격(할인 반영)으로 정렬한다 — 카드와 같은 함수다.
    // [JA] 🐛 以前は定価で並べ替えていた — カードが割引価格を見せると(ProductCard)30% 割引された $70 のものが $80 のものより
    //      後ろに立った。画面に見える価格(割引反映)で並べ替える — カードと同じ関数である。
    const range = skuPriceRange(product?.skus?.[0]);
    if (!range) return 0;
    return pick === "max" ? range.max : range.min;
}

/**
 * 정렬. 새 배열을 돌려주고 입력을 바꾸지 않는다.
 *
 * <p>예전에는 loader 안에서 `productsDB.product.content.sort(...)` 로 응답을 제자리에서
 * 정렬했다. 그 배열은 이제 React Query 캐시에 들어 있고, 캐시를 제자리에서 바꾸면 같은
 * 데이터를 보는 다른 렌더와 어긋난다.
 *
 * <p>[JA] 並べ替え。新しい配列を返し入力を変えない。以前は loader の中で `productsDB.product.content.sort(...)` で応答をその場で並べ替えていた。
 * その配列は今 React Query のキャッシュに入っており、キャッシュをその場で変えると同じデータを見る別のレンダーとずれる。
 */
export function sortProducts(products, sortQuery) {
    const list = [...(products ?? [])];

    switch (sortQuery) {
        case "popular":
            return list
                .map(sortSkusBySold)
                .sort((a, b) => Number(b.rating) - Number(a.rating));
        case "newest":
            return list.sort((a, b) => timeOf(b.createdAt) - timeOf(a.createdAt));
        case "topSelling":
            // 상품 순서는 그대로 두고 색상(sku)만 많이 팔린 순으로 — 기존 동작이다.
            // [JA] 商品の順序はそのままにして色(sku)だけをよく売れた順に — 既存の動作である。
            return list.map(sortSkusBySold);
        case "topReviewed":
            return list.sort((a, b) => Number(b.rating) - Number(a.rating));
        case "priceHighToLow":
            return list.sort((a, b) => firstSkuPrice(b, "max") - firstSkuPrice(a, "max"));
        case "priceLowToHight":
            return list.sort((a, b) => firstSkuPrice(a, "min") - firstSkuPrice(b, "min"));
        default:
            return list;
    }
}

/** 한 페이지를 가져온다. loader 와 useInfiniteQuery 가 함께 쓴다. */
// [JA] 1 ページを取得する。loader と useInfiniteQuery が共有する。
export async function fetchBrowsePage(searchParams, page) {
    const res = await getRequest("/catalog-service/api/search", {
        params: buildSearchRequest(searchParams, page),
    });

    return res?.status === 200 ? res.data : null;
}

export async function loader({ request }) {
    const searchParams = new URL(request.url).searchParams;

    try {
        // 무한 스크롤의 시작점은 언제나 1페이지다. URL 에 남아 있을 수 있는 `page` 는
        // 보지 않는다 — 예전 페이지네이션 링크를 북마크해 둔 경우 2페이지부터 시작해
        // 앞의 상품이 통째로 빠진 목록이 된다.
        // [JA] 無限スクロールの開始点は常に 1 ページ目である。URL に残っているかもしれない `page` は
        //      見ない — 以前のページネーションのリンクをブックマークしていた場合、2 ページ目から始まり
        //      前の商品が丸ごと抜けた一覧になる。
        return await fetchBrowsePage(searchParams, 1);
    } catch (err) {
        // loader 가 null 을 돌려주면 화면은 "상품이 없습니다" 를 그린다. 왜 비었는지는
        // 이 한 줄에만 남으므로 운영 번들에서 지워지면 안 된다.
        // [JA] loader が null を返すと画面は「商品がありません」を描く。なぜ空なのかは
        //      この一行にしか残らないので、本番バンドルから消されてはならない。
        console.error("[browse] 목록을 불러오지 못했습니다", err);
    }

    return null;
}
