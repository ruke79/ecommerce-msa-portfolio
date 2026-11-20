import { useMemo } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";

import AllProduct from "components/admin/Product";
import { getAdminProducts } from "util/api";
import { useInfiniteScroll } from "hook/hooks";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 관리자 상품 목록 페이지.
 *
 * <p>⚠️ 예전에는 `useLoaderData().data` 를 읽었는데 <b>라우트의 loader 가 주석 처리돼
 * 있었다</b>(App.jsx — lazy 라우트와 loader 를 같이 못 쓴다는 메모와 함께). loader 가
 * 없으면 useLoaderData 는 undefined 를 돌려주므로 이 페이지는 진입 즉시
 * `undefined.data` 로 터졌다. 그 메모가 시킨 대로 컴포넌트 안에서 가져온다.
 *
 * <p>🐛 <b>그 다음 문제는 한 번에 전부 받는 것이었다.</b>
 * `GET /api/product/products` 는 이름과 달리 {@code warmUpProductCaches} 를 거쳐 전
 * 카테고리 × 전 서브카테고리를 돌며 <b>카탈로그 전체를 한 배열로</b> 돌려주고, 그
 * 결과를 Redis 키 하나에 통째로 담았다. 게다가 `/api/product/**` 는 공개 경로라
 * 로그인하지 않아도 전 카탈로그를 받아 갈 수 있었다.
 *
 * <p>관리자 전용 커서 페이징 엔드포인트로 옮기고(`/api/content/admin/products`),
 * 아래로 스크롤하면 다음 묶음을 받는다.
 *
 * <p>[JA] 管理者商品一覧ページ。⚠️ 以前は `useLoaderData().data` を読んでいたが<b>ルートの loader がコメントアウトされていた</b>(App.jsx — lazy ルートと
 * loader は一緒に使えないというメモ付き)。loader がなければ useLoaderData は undefined を返すので、このページは入った瞬間 `undefined.data` で落ちた。
 * そのメモの指示どおりコンポーネントの中で取得する。🐛 <b>次の問題は一度に全部受け取ることだった。</b>`GET /api/product/products` は名前に反して
 * {@code warmUpProductCaches} を経由し全カテゴリ × 全サブカテゴリを回って<b>カタログ全体を一つの配列で</b>返し、その結果を Redis キー一つに丸ごと入れていた。
 * さらに `/api/product/**` は公開パスなので、ログインしなくても全カタログを持っていけた。管理者専用のカーソルページングエンドポイントに移し
 * (`/api/content/admin/products`)、下へスクロールすると次のまとまりを受け取る。
 */
const PAGE_SIZE = 20;

/** 서버가 "더 없음" 을 나타내는 값. ProductResponse.LAST_CURSOR 와 같다. */
// [JA] サーバーが「もうない」を表す値。ProductResponse.LAST_CURSOR と同じ。
const LAST_CURSOR = -1;

const Products = () => {
    const { t } = useTranslation();
    const {
        data,
        error,
        fetchNextPage,
        hasNextPage,
        isFetchingNextPage,
        isPending,
    } = useInfiniteQuery({
        queryKey: ["admin-products"],
        queryFn: ({ pageParam }) => getAdminProducts({ cursor: pageParam, pageSize: PAGE_SIZE }),
        initialPageParam: LAST_CURSOR,
        getNextPageParam: (lastPage) => {
            // 서버가 다음 커서를 계산해 준다. -1 이면 끝이다.
            // [JA] サーバーが次のカーソルを計算してくれる。-1 なら終わりである。
            const next = lastPage?.nextCursor;
            if (next === undefined || next === null || Number(next) === LAST_CURSOR) {
                return undefined;
            }
            // 빈 페이지가 오면 멈춘다 — 커서가 제자리에 머무는 응답이 오면 무한 루프가 된다.
            // [JA] 空のページが来たら止める — カーソルがその場に留まる応答が来ると無限ループになる。
            if (!lastPage?.contents?.length) {
                return undefined;
            }
            return next;
        },
        // 관리자는 방금 등록한 상품을 확인하러 온다. 오래 캐시하지 않는다.
        // [JA] 管理者は今登録した商品を確認しに来る。長くキャッシュしない。
        staleTime: 0,
        // 목록 한 묶음을 못 받았다고 화면 전체가 ErrorBoundary 로 날아가면 안 된다.
        // [JA] 一覧の一まとまりを受け取れなかったからといって画面全体が ErrorBoundary に飛んではならない。
        throwOnError: false,
    });

    const pages = useMemo(() => data?.pages ?? [], [data]);

    const products = useMemo(() => {
        const seen = new Set();
        const merged = [];
        for (const page of pages) {
            for (const product of page?.contents ?? []) {
                if (product?.id != null && seen.has(product.id)) continue;
                if (product?.id != null) seen.add(product.id);
                merged.push(product);
            }
        }
        return merged;
    }, [pages]);

    // 첫 페이지만 총 건수를 센다(서버가 그 뒤로는 -1 을 준다).
    // [JA] 最初のページだけ総件数を数える(サーバーはそれ以降 -1 を返す)。
    const total = pages[0]?.totalElements ?? null;

    const sentinelRef = useInfiniteScroll({
        hasNextPage: Boolean(hasNextPage) && !isFetchingNextPage,
        fetchNextPage,
    });

    if (isPending) {
        return <div className="p-4 text-sm text-slate-500">{t("admin.products.loading")}</div>;
    }

    return (
        <>
            {total != null && total >= 0 && (
                <p className="px-4 pt-4 text-sm text-slate-500">{t("admin.products.total", { count: total })}</p>
            )}

            <AllProduct products={products} />

            {/* 감시 대상. 화면에 들어오면 다음 묶음을 부른다. */}
            {/* [JA] 監視対象。画面に入ると次のまとまりを呼ぶ。 */}
            <div ref={sentinelRef} className="w-full h-1" aria-hidden="true" />

            {isFetchingNextPage && (
                <p className="p-4 text-center text-sm text-slate-500">{t("admin.products.loading_more")}</p>
            )}

            {error && (
                <div className="p-4 flex flex-col items-center gap-2">
                    <span className="text-sm text-red-500">
                        {t("admin.products.load_more_failed")}
                    </span>
                    <button
                        type="button"
                        onClick={() => fetchNextPage()}
                        className="px-3 py-1.5 text-sm rounded border border-slate-300 hover:bg-slate-50"
                    >
                        {t("admin.products.retry")}
                    </button>
                </div>
            )}

            {!hasNextPage && products.length > 0 && (
                <p className="p-4 text-center text-sm text-slate-400">
                    {t("admin.products.all_seen", { count: products.length })}
                </p>
            )}

            {products.length === 0 && !error && (
                <p className="p-4 text-center text-sm text-slate-500">{t("admin.products.empty")}</p>
            )}
        </>
    );
};

export default Products;
